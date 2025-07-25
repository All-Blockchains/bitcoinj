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

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Difficulty;
import org.bitcoinj.base.Network;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.crypto.DumpedPrivateKey;
import org.bitcoinj.params.BitcoinNetworkParams;
import org.bitcoinj.params.Networks;
import org.bitcoinj.script.ScriptExecution;
import org.bitcoinj.base.utils.MonetaryFormat;
import org.bitcoinj.utils.VersionTally;

import java.math.BigInteger;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * <p>NetworkParameters contains the data needed for working with an instantiation of a Bitcoin chain.</p>
 *
 * <p>This is an abstract class, concrete instantiations can be found in the params package. There are four:
 * one for the main network ({@link org.bitcoinj.params.MainNetParams}), one for the public test network, and two others that are
 * intended for unit testing and local app development purposes. Although this class contains some aliases for
 * them, you are encouraged to call the static get() methods on each specific params class directly.</p>
 */
public abstract class NetworkParameters {
    // TODO: Seed nodes should be here as well.

    protected Difficulty maxTarget;
    protected int port;
    protected int packetMagic;  // Indicates message origin network and is used to seek to the next message when stream state is unknown.
    protected int dumpedPrivateKeyHeader;
    protected int interval;
    protected int targetTimespan;
    protected int bip32HeaderP2PKHpub;
    protected int bip32HeaderP2PKHpriv;
    protected int bip32HeaderP2WPKHpub;
    protected int bip32HeaderP2WPKHpriv;

    /** Used to check majorities for block version upgrade */
    protected int majorityEnforceBlockUpgrade;
    protected int majorityRejectBlockOutdated;
    protected int majorityWindow;

    /**
     * See getId()
     */
    protected final String id;
    protected final Network network;

    /**
     * The depth of blocks required for a coinbase transaction to be spendable.
     */
    protected int spendableCoinbaseDepth;
    protected int subsidyDecreaseBlockCount;
    
    protected String[] dnsSeeds;
    protected int[] addrSeeds;
    protected Map<Integer, Sha256Hash> checkpoints = new HashMap<>();
    protected volatile transient MessageSerializer defaultSerializer = null;

    protected NetworkParameters(Network network) {
        this.network = network;
        this.id = network.id();
    }

    public static final int TARGET_TIMESPAN = 14 * 24 * 60 * 60;  // 2 weeks per difficulty cycle, on average.
    public static final int TARGET_SPACING = 10 * 60;  // 10 minutes per block.
    public static final int INTERVAL = TARGET_TIMESPAN / TARGET_SPACING;
    
    /**
     * Blocks with a timestamp after this should enforce BIP 16, aka "Pay to script hash". This BIP changed the
     * network rules in a soft-forking manner, that is, blocks that don't follow the rules are accepted but not
     * mined upon and thus will be quickly re-orged out as long as the majority are enforcing the rule.
     */
    public static final Instant BIP16_ENFORCE_TIME = Instant.ofEpochSecond(1333238400);

    /**
     * A Java package style string acting as unique ID for these parameters
     * @return network id string
     */
    public String getId() {
        return id;
    }

    /**
     * Return the {@link Network} type representing the same Bitcoin-like network that this {@link NetworkParameters}
     * represents. For almost all purposes, {@link Network} or its Bitcoin-specific subtype {@link BitcoinNetwork} is
     * preferable to using {@link NetworkParameters}/{@link BitcoinNetworkParams}. Note that the Bitcoin-specific
     * {@link BitcoinNetworkParams} and its subclasses narrow this return type to {@link BitcoinNetwork}.
     *
     * @return preferred representation of network
     */
    public Network network() {
        return network;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return getId().equals(((NetworkParameters)o).getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId());
    }

    /**
     * Return network parameters for a {@link Network}.
     * <p>
     * Alternative networks will be found if they have been registered with {@link Networks} registry.
     * @param network the network
     * @return the network parameters for the given string ID
     * @throws IllegalArgumentException if unknown network
     */
    public static NetworkParameters of(Network network) {
        return (network instanceof BitcoinNetwork)
                ? BitcoinNetworkParams.of((BitcoinNetwork) network)
                : Networks.find(network).orElseThrow(() -> new IllegalArgumentException("Unknown network"));
    }

    public int getSpendableCoinbaseDepth() {
        return spendableCoinbaseDepth;
    }

    /**
     * Validate the hash for a given block height against checkpoints
     * @param height block height
     * @param hash hash for {@code height}
     * @return true if the block height is either not a checkpoint, or is a checkpoint and the hash matches
     */
    public boolean passesCheckpoint(int height, Sha256Hash hash) {
        Sha256Hash checkpointHash = checkpoints.get(height);
        return checkpointHash == null || checkpointHash.equals(hash);
    }

    /**
     * Is height a checkpoint
     * @param height block height
     * @return true if the given height has a recorded checkpoint
     */
    public boolean isCheckpoint(int height) {
        Sha256Hash checkpointHash = checkpoints.get(height);
        return checkpointHash != null;
    }

    public int getSubsidyDecreaseBlockCount() {
        return subsidyDecreaseBlockCount;
    }

    /**
     * Return DNS names that when resolved, give IP addresses of active peers
     * @return an array of DNS names
     */
    public String[] getDnsSeeds() {
        return dnsSeeds;
    }

    /**
     * Return IP addresses of active peers
     * @return array of IP addresses
     */
    public int[] getAddrSeeds() {
        return addrSeeds;
    }

    /**
     * <p>Genesis block for this chain.</p>
     *
     * <p>The first block in every chain is a well known constant shared between all Bitcoin implementations. For a
     * block to be valid, it must be eventually possible to work backwards to the genesis block by following the
     * prevBlockHash pointers in the block headers.</p>
     *
     * <p>The genesis blocks for both test and main networks contain the timestamp of when they were created,
     * and a message in the coinbase transaction. It says, <i>"The Times 03/Jan/2009 Chancellor on brink of second
     * bailout for banks"</i>.</p>
     * @return genesis block
     */
    public abstract Block getGenesisBlock();

    /**
     * Default TCP port on which to connect to nodes
     * @return default port for this network
     */
    public int getPort() {
        return port;
    }

    /**
     * The header bytes that identify the start of a packet on this network.
     * @return header bytes as a long
     */
    public int getPacketMagic() {
        return packetMagic;
    }

    /**
     * First byte of a base58 encoded dumped private key. See {@link DumpedPrivateKey}.
     * @return the header value
     */
    public int getDumpedPrivateKeyHeader() {
        return dumpedPrivateKeyHeader;
    }

    /**
     * How much time in seconds is supposed to pass between "interval" blocks. If the actual elapsed time is
     * significantly different from this value, the network difficulty formula will produce a different value. Both
     * test and main Bitcoin networks use 2 weeks (1209600 seconds).
     * @return target timespan in seconds
     */
    public int getTargetTimespan() {
        return targetTimespan;
    }

    /**
     * If we are running in testnet-in-a-box mode, we allow connections to nodes with 0 non-genesis blocks.
     * @return true if allowed
     */
    public boolean allowEmptyPeerChain() {
        return true;
    }

    /**
     * How many blocks pass between difficulty adjustment periods. Bitcoin standardises this to be 2016.
     * @return number of blocks
     */
    public int getInterval() {
        return interval;
    }

    /**
     * Maximum target represents the easiest allowable proof of work.
     * @return maximum target integer
     */
    public Difficulty maxTarget() {
        return maxTarget;
    }

    /** @deprecated use {@link #maxTarget()} then {@link Difficulty#asInteger()} */
    @Deprecated
    public BigInteger getMaxTarget() {
        return maxTarget.asInteger();
    }

    /**
     * Returns the 4 byte header for BIP32 wallet P2PKH - public key part.
     * @return the header value
     */
    public int getBip32HeaderP2PKHpub() {
        return bip32HeaderP2PKHpub;
    }

    /**
     * Returns the 4 byte header for BIP32 wallet P2PKH - private key part.
     * @return the header value
     */
    public int getBip32HeaderP2PKHpriv() {
        return bip32HeaderP2PKHpriv;
    }

    /**
     * Returns the 4 byte header for BIP32 wallet P2WPKH - public key part.
     * @return the header value
     */
    public int getBip32HeaderP2WPKHpub() {
        return bip32HeaderP2WPKHpub;
    }

    /**
     * Returns the 4 byte header for BIP32 wallet P2WPKH - private key part.
     * @return the header value
     */
    public int getBip32HeaderP2WPKHpriv() {
        return bip32HeaderP2WPKHpriv;
    }

    /**
     * Return the default serializer for this network. This is a shared serializer.
     * @return the default serializer for this network.
     */
    public final MessageSerializer getDefaultSerializer() {
        // Construct a default serializer if we don't have one
        if (null == this.defaultSerializer) {
            // Don't grab a lock unless we absolutely need it
            synchronized(this) {
                // Now we have a lock, double check there's still no serializer
                // and create one if so.
                if (null == this.defaultSerializer) {
                    // As the serializers are intended to be immutable, creating
                    // two due to a race condition should not be a problem, however
                    // to be safe we ensure only one exists for each network.
                    this.defaultSerializer = getSerializer();
                }
            }
        }
        return defaultSerializer;
    }

    /**
     * Construct and return a custom serializer.
     * @return the serializer
     */
    public abstract BitcoinSerializer getSerializer();

    /**
     * The number of blocks in the last {@link #getMajorityWindow()} blocks
     * at which to trigger a notice to the user to upgrade their client, where
     * the client does not understand those blocks.
     * @return number of blocks
     */
    public int getMajorityEnforceBlockUpgrade() {
        return majorityEnforceBlockUpgrade;
    }

    /**
     * The number of blocks in the last {@link #getMajorityWindow()} blocks
     * at which to enforce the requirement that all new blocks are of the
     * newer type (i.e. outdated blocks are rejected).
     * @return number of blocks
     */
    public int getMajorityRejectBlockOutdated() {
        return majorityRejectBlockOutdated;
    }

    /**
     * The sampling window from which the version numbers of blocks are taken
     * in order to determine if a new block version is now the majority.
     * @return number of blocks
     */
    public int getMajorityWindow() {
        return majorityWindow;
    }

    /**
     * The flags indicating which block validation tests should be applied to
     * the given block. Enables support for alternative blockchains which enable
     * tests based on different criteria.
     * 
     * @param block block to determine flags for.
     * @param height height of the block, if known, null otherwise. Returned
     * tests should be a safe subset if block height is unknown.
     * @param tally caching tally counter
     * @return the flags
     */
    public EnumSet<Block.VerifyFlag> getBlockVerificationFlags(final Block block,
            final VersionTally tally, final Integer height) {
        final EnumSet<Block.VerifyFlag> flags = EnumSet.noneOf(Block.VerifyFlag.class);

        if (block.isBIP34()) {
            final Integer count = tally.getCountAtOrAbove(Block.BLOCK_VERSION_BIP34);
            if (null != count && count >= getMajorityEnforceBlockUpgrade()) {
                flags.add(Block.VerifyFlag.HEIGHT_IN_COINBASE);
            }
        }
        return flags;
    }

    /**
     * The flags indicating which script validation tests should be applied to
     * the given transaction. Enables support for alternative blockchains which enable
     * tests based on different criteria.
     *
     * @param block block the transaction belongs to.
     * @param transaction to determine flags for.
     * @param tally caching tally counter
     * @param height height of the block, if known, null otherwise. Returned
     * tests should be a safe subset if block height is unknown.
     * @return the flags
     */
    public EnumSet<ScriptExecution.VerifyFlag> getTransactionVerificationFlags(final Block block,
                                                                               final Transaction transaction, final VersionTally tally, final Integer height) {
        final EnumSet<ScriptExecution.VerifyFlag> verifyFlags = EnumSet.noneOf(ScriptExecution.VerifyFlag.class);
        if (!block.time().isBefore(NetworkParameters.BIP16_ENFORCE_TIME))
            verifyFlags.add(ScriptExecution.VerifyFlag.P2SH);

        // Start enforcing CHECKLOCKTIMEVERIFY, (BIP65) for block.nVersion=4
        // blocks, when 75% of the network has upgraded:
        if (block.version() >= Block.BLOCK_VERSION_BIP65 &&
            tally.getCountAtOrAbove(Block.BLOCK_VERSION_BIP65) > this.getMajorityEnforceBlockUpgrade()) {
            verifyFlags.add(ScriptExecution.VerifyFlag.CHECKLOCKTIMEVERIFY);
        }

        return verifyFlags;
    }
}
