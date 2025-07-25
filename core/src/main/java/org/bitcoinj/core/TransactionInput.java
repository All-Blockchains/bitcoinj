/*
 * Copyright 2011 Google Inc.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.core;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.base.VarInt;
import org.bitcoinj.base.internal.Buffers;
import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.base.internal.InternalUtils;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptException;
import org.bitcoinj.script.ScriptExecution;
import org.bitcoinj.wallet.DefaultRiskAnalysis;
import org.bitcoinj.wallet.KeyBag;
import org.bitcoinj.wallet.RedeemData;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import static org.bitcoinj.base.internal.Preconditions.checkArgument;

/**
 * <p>A transfer of coins from one address to another creates a transaction in which the outputs
 * can be claimed by the recipient in the input of another transaction. You can imagine a
 * transaction as being a module which is wired up to others, the inputs of one have to be wired
 * to the outputs of another. The exceptions are coinbase transactions, which create new coins.</p>
 * 
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
public class TransactionInput {
    /** Magic sequence number that indicates there is no sequence number. */
    public static final long NO_SEQUENCE = 0xFFFFFFFFL;
    /**
     * BIP68: If this flag set, sequence is NOT interpreted as a relative lock-time.
     */
    public static final long SEQUENCE_LOCKTIME_DISABLE_FLAG = 1L << 31;
    /**
     * BIP68: If sequence encodes a relative lock-time and this flag is set, the relative lock-time has units of 512
     * seconds, otherwise it specifies blocks with a granularity of 1.
     */
    public static final long SEQUENCE_LOCKTIME_TYPE_FLAG = 1L << 22;
    /**
     * BIP68: If sequence encodes a relative lock-time, this mask is applied to extract that lock-time from the sequence
     * field.
     */
    public static final long SEQUENCE_LOCKTIME_MASK = 0x0000ffff;

    private static final byte[] EMPTY_ARRAY = new byte[0];
    // Magic outpoint index that indicates the input is in fact unconnected.
    private static final long UNCONNECTED = 0xFFFFFFFFL;

    @Nullable private Transaction parent;

    // Allows for altering transactions after they were broadcast. Values below NO_SEQUENCE-1 mean it can be altered.
    private final long sequence;
    // Data needed to connect to the output of the transaction we're gathering coins from.
    private TransactionOutPoint outpoint;
    // The "script bytes" might not actually be a script. In coinbase transactions where new coins are minted there
    // is no input transaction, so instead the scriptBytes contains some extra stuff (like a rollover nonce) that we
    // don't care about much. The bytes are turned into a Script object (cached below) on demand via a getter.
    private final byte[] scriptBytes;
    // The Script object obtained from parsing scriptBytes. Only filled in on demand and if the transaction is not
    // coinbase.
    private WeakReference<Script> scriptSig;
    /** Value of the output connected to the input, if known. This field does not participate in equals()/hashCode(). */
    @Nullable
    private Coin value;

    private final TransactionWitness witness;

    /**
     * Creates an input that connects to nothing - used only in creation of coinbase transactions.
     *
     * @param parentTransaction parent transaction
     * @param scriptBytes       arbitrary bytes in the script
     */
    public static TransactionInput coinbaseInput(Transaction parentTransaction, byte[] scriptBytes) {
        Objects.requireNonNull(parentTransaction);
        checkArgument(scriptBytes.length >= 2 && scriptBytes.length <= 100, () ->
                "script must be between 2 and 100 bytes: " + scriptBytes.length);
        return new TransactionInput(parentTransaction, scriptBytes, TransactionOutPoint.UNCONNECTED);
    }

    /**
     * Deserialize this transaction input from a given payload.
     *
     * @param payload           payload to deserialize from
     * @param parentTransaction parent transaction of the input
     * @return read message
     * @throws BufferUnderflowException if the read message extends beyond the remaining bytes of the payload
     */
    public static TransactionInput read(ByteBuffer payload, Transaction parentTransaction) throws BufferUnderflowException, ProtocolException {
        Objects.requireNonNull(parentTransaction);
        TransactionOutPoint outpoint = TransactionOutPoint.read(payload);
        byte[] scriptBytes = Buffers.readLengthPrefixedBytes(payload);
        long sequence = ByteUtils.readUint32(payload);
        return new TransactionInput(parentTransaction, scriptBytes, outpoint, sequence, null);
    }

    public TransactionInput(@Nullable Transaction parentTransaction, byte[] scriptBytes,
                            TransactionOutPoint outpoint) {
        this(parentTransaction, scriptBytes, outpoint, NO_SEQUENCE, null);
    }

    public TransactionInput(@Nullable Transaction parentTransaction, byte[] scriptBytes,
                            TransactionOutPoint outpoint, long sequence) {
        this(parentTransaction, scriptBytes, outpoint, sequence, null);
    }

    public TransactionInput(@Nullable Transaction parentTransaction, byte[] scriptBytes,
                            TransactionOutPoint outpoint, @Nullable Coin value) {
        this(parentTransaction, scriptBytes, outpoint, NO_SEQUENCE, value);
    }

    /** internal use only */
    private TransactionInput(@Nullable Transaction parentTransaction, byte[] scriptBytes, TransactionOutPoint outpoint,
                             long sequence, @Nullable Coin value) {
        this(parentTransaction, null, scriptBytes, outpoint, sequence, value, null);
    }

    /** internal use only */
    public TransactionInput(@Nullable Transaction parentTransaction, byte[] scriptBytes, TransactionOutPoint outpoint,
                            long sequence, @Nullable Coin value, @Nullable TransactionWitness witness) {
        this(parentTransaction, null, scriptBytes, outpoint, sequence, value, witness);
    }

    private TransactionInput(@Nullable Transaction parentTransaction, @Nullable Script scriptSig, byte[] scriptBytes,
                            TransactionOutPoint outpoint, long sequence, @Nullable Coin value,
                            @Nullable TransactionWitness witness) {
        checkArgument(value == null || value.signum() >= 0, () -> "value out of range: " + value);
        parent = parentTransaction;
        this.scriptSig = scriptSig != null ? new WeakReference<>(scriptSig) : null;
        this.scriptBytes = Objects.requireNonNull(scriptBytes);
        this.outpoint = Objects.requireNonNull(outpoint);
        this.sequence = sequence;
        this.value = value;
        this.witness = witness;
    }

    /**
     * Creates an UNSIGNED input that links to the given output
     */
    TransactionInput(Transaction parentTransaction, TransactionOutput output) {
        this(parentTransaction,
                null, EMPTY_ARRAY,
                output.getParentTransaction() != null ?
                        TransactionOutPoint.from(output.getParentTransaction(), output.getIndex()) :
                        TransactionOutPoint.from(output),
                NO_SEQUENCE,
                output.getValue(),
                null);
    }

    /**
     * Gets the index of this input in the parent transaction, or throws if this input is freestanding. Iterates
     * over the parents list to discover this.
     */
    public int getIndex() {
        final int myIndex = getParentTransaction().getInputs().indexOf(this);
        if (myIndex < 0)
            throw new IllegalStateException("Input linked to wrong parent transaction?");
        return myIndex;
    }

    /**
     * Write this transaction input into the given buffer.
     *
     * @param buf buffer to write into
     * @return the buffer
     * @throws BufferOverflowException if the input doesn't fit the remaining buffer
     */
    public ByteBuffer write(ByteBuffer buf) throws BufferOverflowException {
        outpoint.write(buf);
        Buffers.writeLengthPrefixedBytes(buf, scriptBytes);
        ByteUtils.writeInt32LE(sequence, buf);
        return buf;
    }

    /**
     * Allocates a byte array and writes this transaction input into it.
     *
     * @return byte array containing the transaction input
     */
    public byte[] serialize() {
        return write(ByteBuffer.allocate(messageSize())).array();
    }

    /**
     * Return the size of the serialized message. Note that if the message was deserialized from a payload, this
     * size can differ from the size of the original payload.
     *
     * @return size of the serialized message in bytes
     */
    public int messageSize() {
        return TransactionOutPoint.BYTES +
                Buffers.lengthPrefixedBytesSize(scriptBytes) +
                4; // sequence
    }

    /**
     * Coinbase transactions have special inputs with hashes of zero. If this is such an input, returns true.
     */
    public boolean isCoinBase() {
        return outpoint.hash().equals(Sha256Hash.ZERO_HASH) &&
                (outpoint.index() & 0xFFFFFFFFL) == 0xFFFFFFFFL;  // -1 but all is serialized to the wire as unsigned int.
    }

    /**
     * Returns the script that is fed to the referenced output (scriptPubKey) script in order to satisfy it: usually
     * contains signatures and maybe keys, but can contain arbitrary data if the output script accepts it.
     */
    public Script getScriptSig() throws ScriptException {
        // Transactions that generate new coins don't actually have a script. Instead this
        // parameter is overloaded to be something totally different.
        Script script = scriptSig == null ? null : scriptSig.get();
        if (script == null) {
            script = Script.parse(scriptBytes);
            scriptSig = new WeakReference<>(script);
        }
        return script;
    }

    /**
     * Returns a clone of this input, with given scriptSig. The typical use case is transaction signing.
     *
     * @param scriptSig scriptSig for the clone
     * @return clone of input, with given scriptSig
     */
    public TransactionInput withScriptSig(Script scriptSig) {
        Objects.requireNonNull(scriptSig);
        return new TransactionInput(this.parent, scriptSig, scriptSig.program(), this.outpoint, this.sequence,
                this.value, this.witness);
    }

    /**
     * Sequence numbers allow participants in a multi-party transaction signing protocol to create new versions of the
     * transaction independently of each other. Newer versions of a transaction can replace an existing version that's
     * in nodes memory pools if the existing version is time locked. See the Contracts page on the Bitcoin wiki for
     * examples of how you can use this feature to build contract protocols.
     */
    public long getSequenceNumber() {
        return sequence;
    }

    /**
     * Returns a clone of this input, with a given sequence number.
     * <p>
     * Sequence numbers allow participants in a multi-party transaction signing protocol to create new versions of the
     * transaction independently of each other. Newer versions of a transaction can replace an existing version that's
     * in nodes memory pools if the existing version is time locked. See the Contracts page on the Bitcoin wiki for
     * examples of how you can use this feature to build contract protocols.
     *
     * @param sequence sequence number for the clone
     * @return clone of input, with given sequence number
     */
    public TransactionInput withSequence(long sequence) {
        checkArgument(sequence >= 0 && sequence <= ByteUtils.MAX_UNSIGNED_INTEGER, () ->
                "sequence out of range: " + sequence);
        Script scriptSig = this.scriptSig != null ? this.scriptSig.get() : null;
        return new TransactionInput(this.parent, scriptSig, this.scriptBytes, this.outpoint, sequence, this.value,
                this.witness);
    }

    /**
     * @return The previous output transaction reference, as an OutPoint structure.  This contains the 
     * data needed to connect to the output of the transaction we're gathering coins from.
     */
    public TransactionOutPoint getOutpoint() {
        return outpoint;
    }

    /**
     * The "script bytes" might not actually be a script. In coinbase transactions where new coins are minted there
     * is no input transaction, so instead the scriptBytes contains some extra stuff (like a rollover nonce) that we
     * don't care about much. The bytes are turned into a Script object (cached below) on demand via a getter.
     * @return the scriptBytes
     */
    public byte[] getScriptBytes() {
        return Arrays.copyOf(scriptBytes, scriptBytes.length);
    }

    /**
     * Returns a clone of this input, without script bytes. The typical use case is transaction signing.
     *
     * @return clone of input, without script bytes
     */
    public TransactionInput withoutScriptBytes() {
        return new TransactionInput(this.parent, null, TransactionInput.EMPTY_ARRAY, this.outpoint, this.sequence,
                this.value, this.witness);
    }

    /**
     * Returns a clone of this input, with given script bytes. The typical use case is transaction signing.
     *
     * @param scriptBytes script bytes for the clone
     * @return clone of input, with given script bytes
     */
    public TransactionInput withScriptBytes(byte[] scriptBytes) {
        Objects.requireNonNull(scriptBytes);
        return new TransactionInput(this.parent, null, scriptBytes, this.outpoint, this.sequence, this.value,
                this.witness);
    }

    /**
     * @return The Transaction that owns this input.
     */
    public Transaction getParentTransaction() {
        return parent;
    }

    /**
     * @return Value of the output connected to this input, if known. Null if unknown.
     */
    @Nullable
    public Coin getValue() {
        return value;
    }

    /**
     * Get the transaction witness of this input.
     * 
     * @return the witness of the input
     */
    public TransactionWitness getWitness() {
        return witness != null ? witness : TransactionWitness.EMPTY;
    }

    /**
     * Returns a clone of this input, with a given witness. The typical use-case is transaction signing.
     *
     * @param witness witness for the clone
     * @return clone of input, with given witness
     */
    public TransactionInput withWitness(TransactionWitness witness) {
        Objects.requireNonNull(witness);
        Script scriptSig = this.scriptSig != null ? this.scriptSig.get() : null;
        return new TransactionInput(this.parent, scriptSig, this.scriptBytes, this.outpoint, sequence, this.value,
                witness);
    }

    /**
     * Returns a clone of this input, without witness. The typical use-case is transaction signing.
     *
     * @return clone of input, without witness
     */
    public TransactionInput withoutWitness() {
        Script scriptSig = this.scriptSig != null ? this.scriptSig.get() : null;
        return new TransactionInput(this.parent, scriptSig, this.scriptBytes, this.outpoint, sequence, this.value,
                null);
    }

    /**
     * Determine if the transaction has witnesses.
     * 
     * @return true if the transaction has witnesses
     */
    public boolean hasWitness() {
        return witness != null && witness.getPushCount() != 0;
    }

    public enum ConnectionResult {
        NO_SUCH_TX,
        ALREADY_SPENT,
        SUCCESS
    }

    // TODO: Clean all this up once TransactionOutPoint disappears.

    /**
     * Locates the referenced output from the given pool of transactions.
     *
     * @return The TransactionOutput or null if the transactions map doesn't contain the referenced tx.
     */
    @Nullable
    TransactionOutput getConnectedOutput(Map<Sha256Hash, Transaction> transactions) {
        Transaction tx = transactions.get(outpoint.hash());
        if (tx == null)
            return null;
        return tx.getOutput(outpoint);
    }

    /**
     * Alias for getOutpoint().getConnectedRedeemData(keyBag)
     * @see TransactionOutPoint#getConnectedRedeemData(KeyBag)
     */
    @Nullable
    public RedeemData getConnectedRedeemData(KeyBag keyBag) throws ScriptException {
        return getOutpoint().getConnectedRedeemData(keyBag);
    }


    public enum ConnectMode {
        DISCONNECT_ON_CONFLICT,
        ABORT_ON_CONFLICT
    }

    /**
     * Connects this input to the relevant output of the referenced transaction if it's in the given map.
     * Connecting means updating the internal pointers and spent flags. If the mode is to ABORT_ON_CONFLICT then
     * the spent output won't be changed, but the outpoint.fromTx pointer will still be updated.
     *
     * @param transactions Map of txhash to transaction.
     * @param mode   Whether to abort if there's a pre-existing connection or not.
     * @return NO_SUCH_TX if the prevtx wasn't found, ALREADY_SPENT if there was a conflict, SUCCESS if not.
     */
    public ConnectionResult connect(Map<Sha256Hash, Transaction> transactions, ConnectMode mode) {
        Transaction tx = transactions.get(outpoint.hash());
        if (tx == null) {
            return TransactionInput.ConnectionResult.NO_SUCH_TX;
        }
        return connect(tx, mode);
    }

    /**
     * Connects this input to the relevant output of the referenced transaction.
     * Connecting means updating the internal pointers and spent flags. If the mode is to ABORT_ON_CONFLICT then
     * the spent output won't be changed, but the outpoint.fromTx pointer will still be updated.
     *
     * @param transaction The transaction to try.
     * @param mode   Whether to abort if there's a pre-existing connection or not.
     * @return NO_SUCH_TX if transaction is not the prevtx, ALREADY_SPENT if there was a conflict, SUCCESS if not.
     */
    public ConnectionResult connect(Transaction transaction, ConnectMode mode) {
        if (!transaction.getTxId().equals(outpoint.hash()))
            return ConnectionResult.NO_SUCH_TX;
        TransactionOutput out = transaction.getOutput(outpoint);
        if (!out.isAvailableForSpending()) {
            if (getParentTransaction().equals(outpoint.getFromTx())) {
                // Already connected.
                return ConnectionResult.SUCCESS;
            } else if (mode == ConnectMode.DISCONNECT_ON_CONFLICT) {
                out.markAsUnspent();
            } else if (mode == ConnectMode.ABORT_ON_CONFLICT) {
                outpoint = outpoint.connectTransaction(out.getParentTransaction());
                return TransactionInput.ConnectionResult.ALREADY_SPENT;
            }
        }
        connect(out);
        return TransactionInput.ConnectionResult.SUCCESS;
    }

    /** Internal use only: connects this TransactionInput to the given output (updates pointers and spent flags) */
    public void connect(TransactionOutput out) {
        outpoint = outpoint.connectTransaction(out.getParentTransaction());
        out.markAsSpent(this);
        value = out.getValue();
    }

    /**
     * If this input is connected, check the output is connected back to this input and release it if so, making
     * it spendable once again.
     *
     * @return true if the disconnection took place, false if it was not connected.
     */
    public boolean disconnect() {
        TransactionOutput connectedOutput = outpoint.getConnectedOutput();
        if (connectedOutput != null) {
            outpoint = outpoint.disconnectOutput();
        } else {
            // The outpoint is not connected, do nothing.
            return false;
        }

        if (connectedOutput.getSpentBy() == this) {
            // The outpoint was connected to an output, disconnect the output.
            connectedOutput.markAsUnspent();
            return true;
        } else {
            return false;
        }
    }

    /**
     * @return true if this transaction's sequence number is set (ie it may be a part of a time-locked transaction)
     */
    public boolean hasSequence() {
        return sequence != NO_SEQUENCE;
    }

    /**
     * Returns whether this input will cause a transaction to opt into the
     * <a href="https://github.com/bitcoin/bips/blob/master/bip-0125.mediawiki">full replace-by-fee </a> semantics.
     */
    public boolean isOptInFullRBF() {
        return sequence < NO_SEQUENCE - 1;
    }

    /**
     * Returns whether this input, if it belongs to a version 2 (or higher) transaction, has
     * <a href="https://github.com/bitcoin/bips/blob/master/bip-0068.mediawiki">relative lock-time</a> enabled.
     */
    public boolean hasRelativeLockTime() {
        return (sequence & SEQUENCE_LOCKTIME_DISABLE_FLAG) == 0;
    }

    /**
     * For a connected transaction, runs the script against the connected pubkey and verifies they are correct.
     * @throws ScriptException if the script did not verify.
     * @throws VerificationException If the outpoint doesn't match the given output.
     */
    public void verify() throws VerificationException {
        final Transaction fromTx = getOutpoint().getFromTx();
        Objects.requireNonNull(fromTx, "Not connected");
        final TransactionOutput output = fromTx.getOutput(outpoint);
        verify(output);
    }

    /**
     * Verifies that this input can spend the given output. Note that this input must be a part of a transaction.
     * Also note that the consistency of the outpoint will be checked, even if this input has not been connected.
     *
     * @param output the output that this input is supposed to spend.
     * @throws ScriptException If the script doesn't verify.
     * @throws VerificationException If the outpoint doesn't match the given output.
     */
    public void verify(TransactionOutput output) throws VerificationException {
        if (output.parent != null) {
            if (!getOutpoint().hash().equals(output.getParentTransaction().getTxId()))
                throw new VerificationException("This input does not refer to the tx containing the output.");
            if (getOutpoint().index() != output.getIndex())
                throw new VerificationException("This input refers to a different output on the given tx.");
        }
        Script pubKey = output.getScriptPubKey();
        ScriptExecution.correctlySpends(getScriptSig(), getParentTransaction(), getIndex(), getWitness(), getValue(), pubKey,
                ScriptExecution.ALL_VERIFY_FLAGS);
    }

    /**
     * Returns the connected output, assuming the input was connected with
     * {@link TransactionInput#connect(TransactionOutput)} or variants at some point. If it wasn't connected, then
     * this method returns null.
     */
    @Nullable
    public TransactionOutput getConnectedOutput() {
        return getOutpoint().getConnectedOutput();
    }

    /**
     * Returns the connected transaction, assuming the input was connected with
     * {@link TransactionInput#connect(TransactionOutput)} or variants at some point. If it wasn't connected, then
     * this method returns null.
     */
    @Nullable
    public Transaction getConnectedTransaction() {
        return getOutpoint().getFromTx();
    }

    /**
     * <p>Returns either RuleViolation.NONE if the input is standard, or which rule makes it non-standard if so.
     * The "IsStandard" rules control whether the default Bitcoin Core client blocks relay of a tx / refuses to mine it,
     * however, non-standard transactions can still be included in blocks and will be accepted as valid if so.</p>
     *
     * <p>This method simply calls {@code DefaultRiskAnalysis.isInputStandard(this)}.</p>
     */
    public DefaultRiskAnalysis.RuleViolation isStandard() {
        return DefaultRiskAnalysis.isInputStandard(this);
    }

    protected final void setParent(@Nullable Transaction parent) {
        this.parent = parent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransactionInput other = (TransactionInput) o;
        return sequence == other.sequence && parent != null && parent.equals(other.parent)
            && outpoint.equals(other.outpoint) && Arrays.equals(scriptBytes, other.scriptBytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sequence, outpoint, Arrays.hashCode(scriptBytes));
    }

    /**
     * Returns a human-readable debug string.
     */
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("TxIn");
        if (isCoinBase()) {
            s.append(": COINBASE");
        } else {
            s.append(" for [").append(outpoint).append("]: ");
            try {
                s.append(getScriptSig());
                String flags = InternalUtils.commaJoin(hasWitness() ? "witness" : null,
                        hasSequence() ? "sequence: " + Long.toHexString(sequence) : null,
                        isOptInFullRBF() ? "opts into full RBF" : null);
                if (!flags.isEmpty())
                    s.append(" (").append(flags).append(')');
            } catch (ScriptException e) {
                s.append(" [exception: ").append(e.getMessage()).append("]");
            }
        }
        return s.toString();
    }
}
