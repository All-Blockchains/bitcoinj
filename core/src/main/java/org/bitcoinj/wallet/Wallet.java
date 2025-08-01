/*
 * Copyright 2013 Google Inc.
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

package org.bitcoinj.wallet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.math.IntMath;
import com.google.protobuf.ByteString;
import org.bitcoinj.core.internal.GuardedBy;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Network;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.base.internal.PlatformUtils;
import org.bitcoinj.base.internal.TimeUtils;
import org.bitcoinj.base.internal.StreamUtils;
import org.bitcoinj.crypto.AesKey;
import org.bitcoinj.core.AbstractBlockChain;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.Base58;
import org.bitcoinj.base.AddressParser;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.BloomFilter;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.core.FilteredBlock;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.core.Message;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerFilterProvider;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionBag;
import org.bitcoinj.core.TransactionBroadcast;
import org.bitcoinj.core.TransactionBroadcaster;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.core.TransactionConfidence.Listener;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.UTXO;
import org.bitcoinj.core.UTXOProvider;
import org.bitcoinj.core.UTXOProviderException;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.listeners.NewBestBlockListener;
import org.bitcoinj.core.listeners.ReorganizeListener;
import org.bitcoinj.core.listeners.TransactionConfidenceEventListener;
import org.bitcoinj.core.listeners.TransactionReceivedInBlockListener;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.crypto.HDPath;
import org.bitcoinj.crypto.KeyCrypter;
import org.bitcoinj.crypto.KeyCrypterException;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.script.Script;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptChunk;
import org.bitcoinj.script.ScriptException;
import org.bitcoinj.script.ScriptExecution;
import org.bitcoinj.script.ScriptPattern;
import org.bitcoinj.signers.LocalTransactionSigner;
import org.bitcoinj.signers.MissingSigResolutionSigner;
import org.bitcoinj.signers.TransactionSigner;
import org.bitcoinj.utils.BaseTaggableObject;
import org.bitcoinj.base.internal.FutureUtils;
import org.bitcoinj.utils.ListenerRegistration;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.protobuf.wallet.Protos;
import org.bitcoinj.protobuf.wallet.Protos.Wallet.EncryptionType;
import org.bitcoinj.wallet.WalletTransaction.Pool;
import org.bitcoinj.wallet.listeners.CurrentKeyChangeEventListener;
import org.bitcoinj.wallet.listeners.KeyChainEventListener;
import org.bitcoinj.wallet.listeners.ScriptsChangeEventListener;
import org.bitcoinj.wallet.listeners.WalletChangeEventListener;
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener;
import org.bitcoinj.wallet.listeners.WalletCoinsSentEventListener;
import org.bitcoinj.wallet.listeners.WalletReorganizeEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.bitcoinj.base.internal.Preconditions.checkArgument;
import static org.bitcoinj.base.internal.Preconditions.checkState;

// To do list:
//
// - Take all wallet-relevant data out of Transaction and put it into WalletTransaction. Make Transaction immutable.
// - Only store relevant transaction outputs, don't bother storing the rest of the data. Big RAM saving.
// - Split block chain and tx output tracking into a superclass that doesn't have any key or spending related code.
// - Simplify how transactions are tracked and stored: in particular, have the wallet maintain positioning information
//   for transactions independent of the transactions themselves, so the timeline can be walked without having to
//   process and sort every single transaction.
// - Split data persistence out into a backend class and make the wallet transactional, so we can store a wallet
//   in a database not just in RAM.
// - Make clearing of transactions able to only rewind the wallet a certain distance instead of all blocks.
// - Make it scale:
//     - eliminate all the algorithms with quadratic complexity (or worse)
//     - don't require everything to be held in RAM at once
//     - consider allowing eviction of no longer re-orgable transactions or keys that were used up
//
// Finally, find more ways to break the class up and decompose it. Currently every time we move code out, other code
// fills up the lines saved!

/**
 * <p>A Wallet stores keys and a record of transactions that send and receive value from those keys. Using these,
 * it is able to create new transactions that spend the recorded transactions, and this is the fundamental operation
 * of the Bitcoin protocol.</p>
 *
 * <p>To learn more about this class, read <b><a href="https://bitcoinj.github.io/working-with-the-wallet">
 *     working with the wallet.</a></b></p>
 *
 * <p>To fill up a Wallet with transactions, you need to use it in combination with a {@link BlockChain} and various
 * other objects, see the <a href="https://bitcoinj.github.io/getting-started">Getting started</a> tutorial
 * on the website to learn more about how to set everything up.</p>
 *
 * <p>Wallets can be serialized using protocol buffers. You need to save the wallet whenever it changes, there is an
 * auto-save feature that simplifies this for you although you're still responsible for manually triggering a save when
 * your app is about to quit because the auto-save feature waits a moment before actually committing to disk to avoid IO
 * thrashing when the wallet is changing very fast (e.g. due to a block chain sync). See
 * {@link Wallet#autosaveToFile(File, Duration, WalletFiles.Listener)}
 * for more information about this.</p>
 */
public class Wallet extends BaseTaggableObject
    implements NewBestBlockListener, TransactionReceivedInBlockListener, PeerFilterProvider,
        KeyBag, TransactionBag, ReorganizeListener, AddressParser {
    private static final Logger log = LoggerFactory.getLogger(Wallet.class);

    // Ordering: lock > keyChainGroupLock. KeyChainGroup is protected separately to allow fast querying of current receive address
    // even if the wallet itself is busy e.g. saving or processing a big reorg. Useful for reducing UI latency.
    protected final ReentrantLock lock = Threading.lock(Wallet.class);
    protected final ReentrantLock keyChainGroupLock = Threading.lock("Wallet-KeyChainGroup lock");

    private static final int MINIMUM_BLOOM_DATA_LENGTH = 8;

    // The various pools below give quick access to wallet-relevant transactions by the state they're in:
    //
    // Pending:  Transactions that didn't make it into the best chain yet. Pending transactions can be killed if a
    //           double spend against them appears in the best chain, in which case they move to the dead pool.
    //           If a double spend appears in the pending state as well, we update the confidence type
    //           of all txns in conflict to IN_CONFLICT and wait for the miners to resolve the race.
    // Unspent:  Transactions that appeared in the best chain and have outputs we can spend. Note that we store the
    //           entire transaction in memory even though for spending purposes we only really need the outputs, the
    //           reason being that this simplifies handling of re-orgs. It would be worth fixing this in future.
    // Spent:    Transactions that appeared in the best chain but don't have any spendable outputs. They're stored here
    //           for history browsing/auditing reasons only and in future will probably be flushed out to some other
    //           kind of cold storage or just removed.
    // Dead:     Transactions that we believe will never confirm get moved here, out of pending. Note that Bitcoin
    //           Core has no notion of dead-ness: the assumption is that double spends won't happen so there's no
    //           need to notify the user about them. We take a more pessimistic approach and try to track the fact that
    //           transactions have been double spent so applications can do something intelligent (cancel orders, show
    //           to the user in the UI, etc). A transaction can leave dead and move into spent/unspent if there is a
    //           re-org to a chain that doesn't include the double spend.

    private final Map<Sha256Hash, Transaction> pending;
    private final Map<Sha256Hash, Transaction> unspent;
    private final Map<Sha256Hash, Transaction> spent;
    private final Map<Sha256Hash, Transaction> dead;

    // All transactions together.
    protected final Map<Sha256Hash, Transaction> transactions;

    // All the TransactionOutput objects that we could spend (ignoring whether we have the private key or not).
    // Used to speed up various calculations.
    protected final Set<TransactionOutput> myUnspents = new HashSet<>();

    // Transactions that were dropped by the risk analysis system. These are not in any pools and not serialized
    // to disk. We have to keep them around because if we ignore a tx because we think it will never confirm, but
    // then it actually does confirm and does so within the same network session, remote peers will not resend us
    // the tx data along with the Bloom filtered block, as they know we already received it once before
    // (so it would be wasteful to repeat). Thus we keep them around here for a while. If we drop our network
    // connections then the remote peers will forget that we were sent the tx data previously and send it again
    // when relaying a filtered merkleblock.
    private final LinkedHashMap<Sha256Hash, Transaction> riskDropped = new LinkedHashMap<Sha256Hash, Transaction>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Sha256Hash, Transaction> eldest) {
            return size() > 1000;
        }
    };

    // The key chain group is not thread safe, and generally the whole hierarchy of objects should not be mutated
    // outside the wallet lock. So don't expose this object directly via any accessors!
    @GuardedBy("keyChainGroupLock") private final KeyChainGroup keyChainGroup;

    // A list of scripts watched by this wallet.
    @GuardedBy("keyChainGroupLock") private final Set<Script> watchedScripts;

    protected final Network network;
    protected final NetworkParameters params;
    private final AddressParser addressParser;


    @Nullable private Sha256Hash lastBlockSeenHash;
    private int lastBlockSeenHeight;
    @Nullable private Instant lastBlockSeenTime;

    private final List<ListenerRegistration<WalletChangeEventListener>> changeListeners
        = new CopyOnWriteArrayList<>();
    private final List<ListenerRegistration<WalletCoinsReceivedEventListener>> coinsReceivedListeners
        = new CopyOnWriteArrayList<>();
    private final List<ListenerRegistration<WalletCoinsSentEventListener>> coinsSentListeners
        = new CopyOnWriteArrayList<>();
    private final List<ListenerRegistration<WalletReorganizeEventListener>> reorganizeListeners
        = new CopyOnWriteArrayList<>();
    private final List<ListenerRegistration<ScriptsChangeEventListener>> scriptsChangeListeners
        = new CopyOnWriteArrayList<>();
    private final List<ListenerRegistration<TransactionConfidenceEventListener>> transactionConfidenceListeners
        = new CopyOnWriteArrayList<>();

    // A listener that relays confidence changes from the transaction confidence object to the wallet event listener,
    // as a convenience to API users so they don't have to register on every transaction themselves.
    private TransactionConfidence.Listener txConfidenceListener;

    // If a TX hash appears in this set then notifyNewBestBlock will ignore it, as its confidence was already set up
    // in receive() via Transaction.setBlockAppearance(). As the BlockChain always calls notifyNewBestBlock even if
    // it sent transactions to the wallet, without this we'd double count.
    private Set<Sha256Hash> ignoreNextNewBlock;
    // Whether to ignore pending transactions that are considered risky by the configured risk analyzer.
    private boolean acceptRiskyTransactions;
    // Object that performs risk analysis of pending transactions. We might reject transactions that seem like
    // a high risk of being a double spending attack.
    private RiskAnalysis.Analyzer riskAnalyzer = DefaultRiskAnalysis.FACTORY;

    // Stuff for notifying transaction objects that we changed their confidences. The purpose of this is to avoid
    // spuriously sending lots of repeated notifications to listeners that API users aren't really interested in as a
    // side effect of how the code is written (e.g. during re-orgs confidence data gets adjusted multiple times).
    private int onWalletChangedSuppressions;
    private boolean insideReorg;
    private final Map<Transaction, TransactionConfidence.Listener.ChangeReason> confidenceChanged;
    protected volatile WalletFiles vFileManager;
    // Object that is used to send transactions asynchronously when the wallet requires it.
    protected volatile TransactionBroadcaster vTransactionBroadcaster;
    // Money controlled by keys created before this time will be automatically respent to a key
    // that was created after it. Useful when you believe some keys have been compromised.
    // Null means no key rotation is configured.
    @Nullable
    private volatile Instant vKeyRotationTime = null;

    protected final CoinSelector coinSelector;

    // The wallet version. This is an int that can be used to track breaking changes in the wallet format.
    // You can also use it to detect wallets that come from the future (ie they contain features you
    // do not know how to deal with).
    private int version;
    // User-provided description that may help people keep track of what a wallet is for.
    private String description;
    // Stores objects that know how to serialize/unserialize themselves to byte streams and whether they're mandatory
    // or not. The string key comes from the extension itself.
    private final HashMap<String, WalletExtension> extensions;

    // Objects that perform transaction signing. Applied subsequently one after another
    @GuardedBy("lock") private final List<TransactionSigner> signers;

    // If this is set then the wallet selects spendable candidate outputs from a UTXO provider.
    @Nullable private volatile UTXOProvider vUTXOProvider;

    /**
     * Creates a new, empty wallet with a randomly chosen seed and no transactions. Make sure to provide for sufficient
     * backup! Any keys will be derived from the seed. If you want to restore a wallet from disk instead, see
     * {@link #loadFromFile}.
     * @param network network wallet will operate on
     * @param outputScriptType type of addresses (aka output scripts) to generate for receiving
     * @return A new empty wallet
     */
    public static Wallet createDeterministic(Network network, ScriptType outputScriptType) {
        return createDeterministic(network, outputScriptType, KeyChainGroupStructure.BIP32);
    }

    /**
     * Creates a new, empty wallet with a randomly chosen seed and no transactions. Make sure to provide for sufficient
     * backup! Any keys will be derived from the seed. If you want to restore a wallet from disk instead, see
     * {@link #loadFromFile}.
     * @param network network wallet will operate on
     * @param outputScriptType type of addresses (aka output scripts) to generate for receiving
     * @param keyChainGroupStructure structure (e.g. BIP32 or BIP43)
     * @return A new empty wallet
     */
    public static Wallet createDeterministic(Network network, ScriptType outputScriptType, KeyChainGroupStructure keyChainGroupStructure) {
        return new Wallet(network, KeyChainGroup.builder(network, keyChainGroupStructure).fromRandom(outputScriptType).build());
    }

    /**
     * Creates a new, empty wallet with just a basic keychain and no transactions. No deterministic chains will be created
     * automatically. This is meant for when you just want to import a few keys and operate on them.
     * @param network network wallet will operate on
     */
    public static Wallet createBasic(Network network) {
        return new Wallet(network, KeyChainGroup.createBasic(network));
    }

    /**
     * @param network network wallet will operate on
     * @param seed deterministic seed
     * @param outputScriptType type of addresses (aka output scripts) to generate for receiving
     * @return a wallet from a deterministic seed with a default account path
     */
    public static Wallet fromSeed(Network network, DeterministicSeed seed,
            ScriptType outputScriptType) {
        return fromSeed(network, seed, outputScriptType, KeyChainGroupStructure.BIP32);
    }

    /**
     * @param network network wallet will operate on
     * @param seed deterministic seed
     * @param outputScriptType type of addresses (aka output scripts) to generate for receiving
     * @param structure structure for your wallet
     * @return a wallet from a deterministic seed with a default account path
     */
    public static Wallet fromSeed(Network network, DeterministicSeed seed, ScriptType outputScriptType,
            KeyChainGroupStructure structure) {
        return new Wallet(network, KeyChainGroup.builder(network, structure).fromSeed(seed, outputScriptType).build());
    }

    /**
     * @param network network wallet will operate on
     * @param seed deterministic seed
     * @param outputScriptType type of addresses (aka output scripts) to generate for receiving
     * @param accountPath account path to generate receiving addresses on
     * @return an instance of a wallet from a deterministic seed.
     */
    public static Wallet fromSeed(Network network, DeterministicSeed seed, ScriptType outputScriptType,
            HDPath.HDPartialPath accountPath) {
        DeterministicKeyChain chain = DeterministicKeyChain.builder().seed(seed).outputScriptType(outputScriptType)
                .accountPath(accountPath).build();
        return new Wallet(network, KeyChainGroup.builder(network).addChain(chain).build());
    }

    /**
     * Creates a wallet that tracks payments to and from the HD key hierarchy rooted by the given watching key. This HAS
     * to be an account key as returned by {@link DeterministicKeyChain#getWatchingKey()}.
     */
    public static Wallet fromWatchingKey(Network network, DeterministicKey watchKey,
            ScriptType outputScriptType) {
        DeterministicKeyChain chain = DeterministicKeyChain.builder().watch(watchKey).outputScriptType(outputScriptType)
                .build();
        return new Wallet(network, KeyChainGroup.builder(network).addChain(chain).build());
    }

    /**
     * Creates a wallet that tracks payments to and from the HD key hierarchy rooted by the given watching key. The
     * account path is specified.
     * @param network network wallet will operate on
     * @param watchKeyB58 The key in base58 notation
     * @param creationTime creation time of the key (if unknown use {@link #fromWatchingKeyB58(Network, String)}
     * @return a new wallet
     */
    public static Wallet fromWatchingKeyB58(Network network, String watchKeyB58, Instant creationTime) {
        final DeterministicKey watchKey = DeterministicKey.deserializeB58(null, watchKeyB58, network);
        watchKey.setCreationTime(creationTime);
        return fromWatchingKey(network, watchKey, outputScriptTypeFromB58(NetworkParameters.of(network), watchKeyB58));
    }

    /**
     * Creates a wallet that tracks payments to and from the HD key hierarchy rooted by the given watching key. The
     * account path is specified. The key's creation time will be set to {@link DeterministicHierarchy#BIP32_STANDARDISATION_TIME}
     * @param network network wallet will operate on
     * @param watchKeyB58 The key in base58 notation
     * @return a new wallet
     */
    public static Wallet fromWatchingKeyB58(Network network, String watchKeyB58) {
        return fromWatchingKeyB58(network, watchKeyB58, DeterministicHierarchy.BIP32_STANDARDISATION_TIME);
    }

    /**
     * Creates a wallet that tracks payments to and from the HD key hierarchy rooted by the given spending key. This HAS
     * to be an account key as returned by {@link DeterministicKeyChain#getWatchingKey()}. This wallet can also spend.
     * @param network network wallet will operate on
     */
    public static Wallet fromSpendingKey(Network network, DeterministicKey spendKey,
            ScriptType outputScriptType) {
        DeterministicKeyChain chain = DeterministicKeyChain.builder().spend(spendKey).outputScriptType(outputScriptType)
                .build();
        return new Wallet(network, KeyChainGroup.builder(network).addChain(chain).build());
    }

    /**
     * Creates a wallet that tracks payments to and from the HD key hierarchy rooted by the given spending key.
     * @param network network wallet will operate on
     * @param spendingKeyB58 The key in base58 notation
     * @param creationTime creation time of the key (if unknown use {@link #fromWatchingKeyB58(Network, String)}
     * @return a new wallet
     */
    public static Wallet fromSpendingKeyB58(Network network, String spendingKeyB58, Instant creationTime) {
        final DeterministicKey spendKey = DeterministicKey.deserializeB58(null, spendingKeyB58, network);
        spendKey.setCreationTime(creationTime);
        return fromSpendingKey(network, spendKey, outputScriptTypeFromB58(NetworkParameters.of(network), spendingKeyB58));
    }

    /**
     * Creates a wallet that tracks payments to and from the HD key hierarchy rooted by the given spending key.
     * The key's creation time will be set to {@link DeterministicHierarchy#BIP32_STANDARDISATION_TIME}.
     * @param network network wallet will operate on
     * @param spendingKeyB58 The key in base58 notation
     * @return a new wallet
     */
    public static Wallet fromSpendingKeyB58(Network network, String spendingKeyB58) {
        return fromSpendingKeyB58(network, spendingKeyB58, DeterministicHierarchy.BIP32_STANDARDISATION_TIME);
    }

    /**
     * Creates a spending wallet that tracks payments to and from a BIP32-style HD key hierarchy rooted by {@code masterKey} and
     * {@code accountNumber}. The account path must be directly below the master key as in BIP-32.
     * <p>
     * This method should not be used for BIP-43 compliant wallets or accounts not of the form {@code m/accountNumber'}.
     * <p>
     * This wallet will not store the {@code masterKey}, only the account key.
     * @param network network the wallet will operate on
     * @param masterKey root private key (e.g. path {@code m})
     * @param outputScriptType type of addresses (aka output scripts) to generate for receiving
     * @param accountNumber account number to append, resulting in an account path of {@code m/accountNumber'}
     * @return newly created Wallet object
     */
    public static Wallet fromMasterKey(Network network, DeterministicKey masterKey,
                                       ScriptType outputScriptType, ChildNumber accountNumber) {
        DeterministicKey accountKey = HDKeyDerivation.deriveChildKey(masterKey, accountNumber);
        accountKey = accountKey.withoutParent();   // Drop the parent private key, so it won't be used or saved.
        Optional<Instant> creationTime = masterKey.getCreationTime();
        if (creationTime.isPresent())
            accountKey.setCreationTime(creationTime.get());
        else
            accountKey.clearCreationTime();
        DeterministicKeyChain chain = DeterministicKeyChain.builder().spend(accountKey)
                .outputScriptType(outputScriptType).build();
        return new Wallet(network, KeyChainGroup.builder(network).addChain(chain).build());
    }

    private static ScriptType outputScriptTypeFromB58(NetworkParameters params, String base58) {
        int header = ByteBuffer.wrap(Base58.decodeChecked(base58)).getInt();
        if (header == params.getBip32HeaderP2PKHpub() || header == params.getBip32HeaderP2PKHpriv())
            return ScriptType.P2PKH;
        else if (header == params.getBip32HeaderP2WPKHpub() || header == params.getBip32HeaderP2WPKHpriv())
            return ScriptType.P2WPKH;
        else
            throw new IllegalArgumentException(base58.substring(0, 4));
    }

    /**
     * Creates a new, empty wallet with no transactions. If you haven't already done so,
     * make sure to provide for sufficient backup of the keys in the {@link KeyChainGroup}.
     * To restore a wallet from disk instead, see {@link #loadFromFile}.
     * @param network network wallet will operate on
     * @param keyChainGroup keychain group to manage keychains
     */
    public Wallet(Network network, KeyChainGroup keyChainGroup) {
        this.network = Objects.requireNonNull(network);
        this.params = NetworkParameters.of(network);
        this.addressParser = AddressParser.getDefault(network);
        this.coinSelector = DefaultCoinSelector.get(network);
        this.keyChainGroup = Objects.requireNonNull(keyChainGroup);
        watchedScripts = new HashSet<>();
        unspent = new HashMap<>();
        spent = new HashMap<>();
        pending = new HashMap<>();
        dead = new HashMap<>();
        transactions = new HashMap<>();
        extensions = new HashMap<>();
        // Use a linked hash map to ensure ordering of event listeners is correct.
        confidenceChanged = new LinkedHashMap<>();
        signers = new ArrayList<>();
        addTransactionSigner(new LocalTransactionSigner());
        createTransientState();
    }

    private void createTransientState() {
        ignoreNextNewBlock = new HashSet<>();
        txConfidenceListener = (confidence, reason) -> {
            // This will run on the user code thread so we shouldn't do anything too complicated here.
            // We only want to queue a wallet changed event and auto-save if the number of peers announcing
            // the transaction has changed, as that confidence change is made by the networking code which
            // doesn't necessarily know at that point which wallets contain which transactions, so it's up
            // to us to listen for that. Other types of confidence changes (type, etc) are triggered by us,
            // so we'll queue up a wallet change event in other parts of the code.
            if (reason == Listener.ChangeReason.SEEN_PEERS) {
                lock.lock();
                try {
                    checkBalanceFuturesLocked();
                    Transaction tx = getTransaction(confidence.getTransactionHash());
                    queueOnTransactionConfidenceChanged(tx);
                    maybeQueueOnWalletChanged();
                } finally {
                    lock.unlock();
                }
            }
        };
        acceptRiskyTransactions = false;
    }

    public Network network() {
        return network;
    }

    /**
     * Parse an address string using all formats this wallet knows about for the wallet's network type
     * @param addressString Address string to parse
     * @return A validated address
     * @throws AddressFormatException if invalid string
     */
    @Override
    public Address parseAddress(String addressString) throws AddressFormatException {
        return addressParser.parseAddress(addressString);
    }

    /**
     * Gets the active keychains via {@link KeyChainGroup#getActiveKeyChains(Instant)}.
     */
    public List<DeterministicKeyChain> getActiveKeyChains() {
        keyChainGroupLock.lock();
        try {
            Instant keyRotationTime = vKeyRotationTime;
            return keyChainGroup.getActiveKeyChains(keyRotationTime);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Gets the default active keychain via {@link KeyChainGroup#getActiveKeyChain()}.
     */
    public DeterministicKeyChain getActiveKeyChain() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.getActiveKeyChain();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * <p>Adds given transaction signer to the list of signers. It will be added to the end of the signers list, so if
     * this wallet already has some signers added, given signer will be executed after all of them.</p>
     * <p>Transaction signer should be fully initialized before adding to the wallet, otherwise {@link IllegalStateException}
     * will be thrown</p>
     */
    public final void addTransactionSigner(TransactionSigner signer) {
        lock.lock();
        try {
            if (signer.isReady())
                signers.add(signer);
            else
                throw new IllegalStateException("Signer instance is not ready to be added into Wallet: " + signer.getClass());
        } finally {
            lock.unlock();
        }
    }

    public List<TransactionSigner> getTransactionSigners() {
        lock.lock();
        try {
            return Collections.unmodifiableList(signers);
        } finally {
            lock.unlock();
        }
    }

    // ***************************************************************************************************************

    //region Key Management

    /**
     * Returns a key that hasn't been seen in a transaction yet, and which is suitable for displaying in a wallet
     * user interface as "a convenient key to receive funds on" when the purpose parameter is
     * {@link KeyChain.KeyPurpose#RECEIVE_FUNDS}. The returned key is stable until
     * it's actually seen in a pending or confirmed transaction, at which point this method will start returning
     * a different key (for each purpose independently).
     */
    public DeterministicKey currentKey(KeyChain.KeyPurpose purpose) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.currentKey(purpose);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * An alias for calling {@link #currentKey(KeyChain.KeyPurpose)} with
     * {@link KeyChain.KeyPurpose#RECEIVE_FUNDS} as the parameter.
     */
    public DeterministicKey currentReceiveKey() {
        return currentKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
    }

    /**
     * Returns address for a {@link #currentKey(KeyChain.KeyPurpose)}
     */
    public Address currentAddress(KeyChain.KeyPurpose purpose) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.currentAddress(purpose);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * An alias for calling {@link #currentAddress(KeyChain.KeyPurpose)} with
     * {@link KeyChain.KeyPurpose#RECEIVE_FUNDS} as the parameter.
     */
    public Address currentReceiveAddress() {
        return currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
    }

    /**
     * Returns a key that has not been returned by this method before (fresh). You can think of this as being
     * a newly created key, although the notion of "create" is not really valid for a
     * {@link DeterministicKeyChain}. When the parameter is
     * {@link KeyChain.KeyPurpose#RECEIVE_FUNDS} the returned key is suitable for being put
     * into a receive coins wizard type UI. You should use this when the user is definitely going to hand this key out
     * to someone who wishes to send money.
     */
    public DeterministicKey freshKey(KeyChain.KeyPurpose purpose) {
        return freshKeys(purpose, 1).get(0);
    }

    /**
     * Returns a key/s that has not been returned by this method before (fresh). You can think of this as being
     * a newly created key/s, although the notion of "create" is not really valid for a
     * {@link DeterministicKeyChain}. When the parameter is
     * {@link KeyChain.KeyPurpose#RECEIVE_FUNDS} the returned key is suitable for being put
     * into a receive coins wizard type UI. You should use this when the user is definitely going to hand this key/s out
     * to someone who wishes to send money.
     */
    public List<DeterministicKey> freshKeys(KeyChain.KeyPurpose purpose, int numberOfKeys) {
        List<DeterministicKey> keys;
        keyChainGroupLock.lock();
        try {
            keys = keyChainGroup.freshKeys(purpose, numberOfKeys);
        } finally {
            keyChainGroupLock.unlock();
        }
        // Do we really need an immediate hard save? Arguably all this is doing is saving the 'current' key
        // and that's not quite so important, so we could coalesce for more performance.
        saveNow();
        return keys;
    }

    /**
     * An alias for calling {@link #freshKey(KeyChain.KeyPurpose)} with
     * {@link KeyChain.KeyPurpose#RECEIVE_FUNDS} as the parameter.
     */
    public DeterministicKey freshReceiveKey() {
        return freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
    }

    /**
     * Returns address for a {@link #freshKey(KeyChain.KeyPurpose)}
     */
    public Address freshAddress(KeyChain.KeyPurpose purpose) {
        Address address;
        keyChainGroupLock.lock();
        try {
            address = keyChainGroup.freshAddress(purpose);
        } finally {
            keyChainGroupLock.unlock();
        }
        saveNow();
        return address;
    }

    /**
     * An alias for calling {@link #freshAddress(KeyChain.KeyPurpose)} with
     * {@link KeyChain.KeyPurpose#RECEIVE_FUNDS} as the parameter.
     */
    public Address freshReceiveAddress() {
        return freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
    }

    /**
     * <p>Returns a fresh receive address for a given {@link ScriptType}.</p>
     * <p>This method is meant for when you really need a fallback address. Normally, you should be
     * using {@link #freshAddress(KeyChain.KeyPurpose)} or
     * {@link #currentAddress(KeyChain.KeyPurpose)}.</p>
     */
    public Address freshReceiveAddress(ScriptType scriptType) {
        Address address;
        keyChainGroupLock.lock();
        try {
            Instant keyRotationTime = vKeyRotationTime;
            address = keyChainGroup.freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS, scriptType, keyRotationTime);
        } finally {
            keyChainGroupLock.unlock();
        }
        saveNow();
        return address;
    }

    /**
     * Returns only the keys that have been issued by {@link #freshReceiveKey()}, {@link #freshReceiveAddress()},
     * {@link #currentReceiveKey()} or {@link #currentReceiveAddress()}.
     */
    public List<ECKey> getIssuedReceiveKeys() {
        keyChainGroupLock.lock();
        try {
            List<ECKey> keys = new LinkedList<>();
            Instant keyRotationTime = vKeyRotationTime;
            for (final DeterministicKeyChain chain : keyChainGroup.getActiveKeyChains(keyRotationTime))
                keys.addAll(chain.getIssuedReceiveKeys());
            return keys;
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns only the addresses that have been issued by {@link #freshReceiveKey()}, {@link #freshReceiveAddress()},
     * {@link #currentReceiveKey()} or {@link #currentReceiveAddress()}.
     */
    public List<Address> getIssuedReceiveAddresses() {
        keyChainGroupLock.lock();
        try {
            List<Address> addresses = new ArrayList<>();
            Instant keyRotationTime = vKeyRotationTime;
            for (final DeterministicKeyChain chain : keyChainGroup.getActiveKeyChains(keyRotationTime)) {
                ScriptType outputScriptType = chain.getOutputScriptType();
                for (ECKey key : chain.getIssuedReceiveKeys())
                    addresses.add(key.toAddress(outputScriptType, network()));
            }
            return addresses;
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Upgrades the wallet to be deterministic (BIP32). You should call this, possibly providing the users encryption
     * key, after loading a wallet produced by previous versions of bitcoinj. If the wallet is encrypted the key
     * <b>must</b> be provided, due to the way the seed is derived deterministically from private key bytes: failing
     * to do this will result in an exception being thrown. For non-encrypted wallets, the upgrade will be done for
     * you automatically the first time a new key is requested (this happens when spending due to the change address).
     */
    public void upgradeToDeterministic(ScriptType outputScriptType, @Nullable AesKey aesKey)
            throws DeterministicUpgradeRequiresPassword {
        upgradeToDeterministic(outputScriptType, KeyChainGroupStructure.BIP32, aesKey);
    }

    /**
     * Upgrades the wallet to be deterministic (BIP32). You should call this, possibly providing the users encryption
     * key, after loading a wallet produced by previous versions of bitcoinj. If the wallet is encrypted the key
     * <b>must</b> be provided, due to the way the seed is derived deterministically from private key bytes: failing
     * to do this will result in an exception being thrown. For non-encrypted wallets, the upgrade will be done for
     * you automatically the first time a new key is requested (this happens when spending due to the change address).
     */
    public void upgradeToDeterministic(ScriptType outputScriptType, KeyChainGroupStructure structure,
                                       @Nullable AesKey aesKey) throws DeterministicUpgradeRequiresPassword {
        keyChainGroupLock.lock();
        try {
            Instant keyRotationTime = vKeyRotationTime;
            keyChainGroup.upgradeToDeterministic(outputScriptType, structure, keyRotationTime, aesKey);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns true if the wallet contains random keys and no HD chains, in which case you should call
     * {@link #upgradeToDeterministic(ScriptType, AesKey)} before attempting to do anything
     * that would require a new address or key.
     */
    public boolean isDeterministicUpgradeRequired(ScriptType outputScriptType) {
        keyChainGroupLock.lock();
        try {
            Instant keyRotationTime = vKeyRotationTime;
            return keyChainGroup.isDeterministicUpgradeRequired(outputScriptType, keyRotationTime);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns a snapshot of the watched scripts. This view is not live.
     */
    public List<Script> getWatchedScripts() {
        keyChainGroupLock.lock();
        try {
            return new ArrayList<>(watchedScripts);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Removes the given key from the basicKeyChain. Be very careful with this - losing a private key <b>destroys the
     * money associated with it</b>.
     * @return Whether the key was removed or not.
     */
    public boolean removeKey(ECKey key) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.removeImportedKey(key);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns the number of keys in the key chain group, including lookahead keys.
     */
    public int getKeyChainGroupSize() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.numKeys();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    @VisibleForTesting
    public int getKeyChainGroupCombinedKeyLookaheadEpochs() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.getCombinedKeyLookaheadEpochs();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns a list of the non-deterministic keys that have been imported into the wallet, or the empty list if none.
     */
    public List<ECKey> getImportedKeys() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.getImportedKeys();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** Returns the address used for change outputs. Note: this will probably go away in future. */
    public Address currentChangeAddress() {
        return currentAddress(KeyChain.KeyPurpose.CHANGE);
    }

    /**
     * <p>Imports the given ECKey to the wallet.</p>
     *
     * <p>If the wallet is configured to auto save to a file, triggers a save immediately. Runs the onKeysAdded event
     * handler. If the key already exists in the wallet, does nothing and returns false.</p>
     */
    public boolean importKey(ECKey key) {
        return importKeys(Collections.singletonList(key)) == 1;
    }

    /**
     * Imports the given keys to the wallet.
     * If {@link Wallet#autosaveToFile(File, Duration, WalletFiles.Listener)}
     * has been called, triggers an auto save bypassing the normal coalescing delay and event handlers.
     * Returns the number of keys added, after duplicates are ignored. The onKeyAdded event will be called for each key
     * in the list that was not already present.
     */
    public int importKeys(final List<ECKey> keys) {
        // API usage check.
        checkNoDeterministicKeys(keys);
        int result;
        keyChainGroupLock.lock();
        try {
            result = keyChainGroup.importKeys(keys);
        } finally {
            keyChainGroupLock.unlock();
        }
        saveNow();
        return result;
    }

    private void checkNoDeterministicKeys(List<ECKey> keys) {
        // Watch out for someone doing wallet.importKey(wallet.freshReceiveKey()); or equivalent: we never tested this.
        for (ECKey key : keys)
            if (key instanceof DeterministicKey)
                throw new IllegalArgumentException("Cannot import HD keys back into the wallet");
    }

    /** Takes a list of keys and a password, then encrypts and imports them in one step using the current keycrypter. */
    public int importKeysAndEncrypt(final List<ECKey> keys, CharSequence password) {
        keyChainGroupLock.lock();
        try {
            Objects.requireNonNull(getKeyCrypter(), "Wallet is not encrypted");
            return importKeysAndEncrypt(keys, getKeyCrypter().deriveKey(password));
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** Takes a list of keys and an AES key, then encrypts and imports them in one step using the current keycrypter. */
    public int importKeysAndEncrypt(final List<ECKey> keys, AesKey aesKey) {
        keyChainGroupLock.lock();
        try {
            checkNoDeterministicKeys(keys);
            return keyChainGroup.importKeysAndEncrypt(keys, aesKey);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Add a pre-configured keychain to the wallet.  Useful for setting up a complex keychain,
     * such as for a married wallet (which is not supported any more).
     */
    public void addAndActivateHDChain(DeterministicKeyChain chain) {
        keyChainGroupLock.lock();
        try {
            keyChainGroup.addAndActivateHDChain(chain);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** See {@link DeterministicKeyChain#setLookaheadSize(int)} for more info on this. */
    public int getKeyChainGroupLookaheadSize() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.getLookaheadSize();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** See {@link DeterministicKeyChain#setLookaheadThreshold(int)} for more info on this. */
    public int getKeyChainGroupLookaheadThreshold() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.getLookaheadThreshold();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns a public-only DeterministicKey that can be used to set up a watching wallet: that is, a wallet that
     * can import transactions from the block chain just as the normal wallet can, but which cannot spend. Watching
     * wallets are very useful for things like web servers that accept payments. This key corresponds to the account
     * zero key in the recommended BIP32 hierarchy.
     */
    public DeterministicKey getWatchingKey() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.getActiveKeyChain().getWatchingKey();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns whether this wallet consists entirely of watching keys (unencrypted keys with no private part). Mixed
     * wallets are forbidden.
     * 
     * @throws IllegalStateException
     *             if there are no keys, or if there is a mix between watching and non-watching keys.
     */
    public boolean isWatching() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.isWatching();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Return true if we are watching this address.
     */
    public boolean isAddressWatched(Address address) {
        Script script = ScriptBuilder.createOutputScript(address);
        return isWatchedScript(script);
    }

    /**
     * Same as {@link #addWatchedAddress(Address, Instant)} with the current time as the creation time.
     */
    public boolean addWatchedAddress(final Address address) {
        Instant now = TimeUtils.currentTime();
        return addWatchedAddresses(Collections.singletonList(address), now) == 1;
    }

    /**
     * Adds the given address to the wallet to be watched. Outputs can be retrieved by {@link #getWatchedOutputs(boolean)}.
     *
     * @param creationTime creation time, for scanning the blockchain
     * @return whether the address was added successfully (not already present)
     */
    public boolean addWatchedAddress(final Address address, Instant creationTime) {
        return addWatchedAddresses(Collections.singletonList(address), creationTime) == 1;
    }

    /**
     * Adds the given addresses to the wallet to be watched. Outputs can be retrieved
     * by {@link #getWatchedOutputs(boolean)}.
     * @param addresses addresses to be watched
     * @param creationTime creation time of the addresses
     * @return how many addresses were added successfully
     */
    public int addWatchedAddresses(final List<Address> addresses, Instant creationTime) {
        List<Script> scripts = new ArrayList<>();
        for (Address address : addresses) {
            Script script = ScriptBuilder.createOutputScript(address, creationTime);
            scripts.add(script);
        }
        return addWatchedScripts(scripts);
    }

    /**
     * Adds the given addresses to the wallet to be watched. Outputs can be retrieved
     * by {@link #getWatchedOutputs(boolean)}. Use this if the creation time of the addresses is unknown.
     * @param addresses addresses to be watched
     * @return how many addresses were added successfully
     */
    public int addWatchedAddresses(final List<Address> addresses) {
        List<Script> scripts = new ArrayList<>();
        for (Address address : addresses) {
            Script script = ScriptBuilder.createOutputScript(address);
            scripts.add(script);
        }
        return addWatchedScripts(scripts);
    }

    /**
     * Adds the given output scripts to the wallet to be watched. Outputs can be retrieved by {@link #getWatchedOutputs(boolean)}.
     * If a script is already being watched, the object is replaced with the one in the given list. As {@link Script}
     * equality is defined in terms of program bytes only this lets you update metadata such as creation time. Note that
     * you should be careful not to add scripts with a creation time of zero (the default!) because otherwise it will
     * disable the important wallet checkpointing optimisation.
     *
     * @return how many scripts were added successfully
     */
    public int addWatchedScripts(final List<Script> scripts) {
        int added = 0;
        keyChainGroupLock.lock();
        try {
            for (final Script script : scripts) {
                // Script.equals/hashCode() only takes into account the program bytes, so this step lets the user replace
                // a script in the wallet with an incorrect creation time.
                if (watchedScripts.contains(script))
                    watchedScripts.remove(script);
                if (!script.creationTime().isPresent())
                    log.warn("Adding a script to the wallet with a creation time of zero, this will disable the checkpointing optimization!    {}", script);
                watchedScripts.add(script);
                added++;
            }
        } finally {
            keyChainGroupLock.unlock();
        }
        if (added > 0) {
            queueOnScriptsChanged(scripts, true);
            saveNow();
        }
        return added;
    }

    /**
     * Removes the given output scripts from the wallet that were being watched.
     *
     * @return true if successful
     */
    public boolean removeWatchedAddress(final Address address) {
        return removeWatchedAddresses(Collections.singletonList(address));
    }

    /**
     * Removes the given output scripts from the wallet that were being watched.
     *
     * @return true if successful
     */
    public boolean removeWatchedAddresses(final List<Address> addresses) {
        List<Script> scripts = new ArrayList<>();

        for (Address address : addresses) {
            Script script = ScriptBuilder.createOutputScript(address);
            scripts.add(script);
        }

        return removeWatchedScripts(scripts);
    }

    /**
     * Removes the given output scripts from the wallet that were being watched.
     *
     * @return true if successful
     */
    public boolean removeWatchedScripts(final List<Script> scripts) {
        lock.lock();
        try {
            for (final Script script : scripts) {
                if (!watchedScripts.contains(script))
                    continue;

                watchedScripts.remove(script);
            }

            queueOnScriptsChanged(scripts, false);
            saveNow();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns all addresses watched by this wallet.
     */
    public List<Address> getWatchedAddresses() {
        keyChainGroupLock.lock();
        try {
            List<Address> addresses = new LinkedList<>();
            for (Script script : watchedScripts)
                if (ScriptPattern.isP2PKH(script))
                    addresses.add(script.getToAddress(network));
            return addresses;
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Locates a keypair from the basicKeyChain given the hash of the public key. This is needed when finding out which
     * key we need to use to redeem a transaction output.
     *
     * @return ECKey object or null if no such key was found.
     */
    @Override
    @Nullable
    public ECKey findKeyFromPubKeyHash(byte[] pubKeyHash, @Nullable ScriptType scriptType) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.findKeyFromPubKeyHash(pubKeyHash, scriptType);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** Returns true if the given key is in the wallet, false otherwise. Currently an O(N) operation. */
    public boolean hasKey(ECKey key) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.hasKey(key);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns true if the address is belongs to this wallet.
     */
    public boolean isAddressMine(Address address) {
        final ScriptType scriptType = address.getOutputScriptType();
        if (scriptType == ScriptType.P2PKH || scriptType == ScriptType.P2WPKH)
            return isPubKeyHashMine(address.getHash(), scriptType);
        else if (scriptType == ScriptType.P2SH)
            return isPayToScriptHashMine(address.getHash());
        else if (scriptType == ScriptType.P2WSH || scriptType == ScriptType.P2TR)
            return false;
        else
            throw new IllegalArgumentException(address.toString());
    }

    @Override
    public boolean isPubKeyHashMine(byte[] pubKeyHash, @Nullable ScriptType scriptType) {
        return findKeyFromPubKeyHash(pubKeyHash, scriptType) != null;
    }

    @Override
    public boolean isWatchedScript(Script script) {
        keyChainGroupLock.lock();
        try {
            return watchedScripts.contains(script);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Locates a keypair from the wallet given the corresponding address.
     * @return ECKey or null if no such key was found.
     */
    public ECKey findKeyFromAddress(Address address) {
        final ScriptType scriptType = address.getOutputScriptType();
        if (scriptType == ScriptType.P2PKH || scriptType == ScriptType.P2WPKH)
            return findKeyFromPubKeyHash(address.getHash(), scriptType);
        else
            return null;
    }

    /**
     * Locates a keypair from the basicKeyChain given the raw public key bytes.
     * @return ECKey or null if no such key was found.
     */
    @Override
    @Nullable
    public ECKey findKeyFromPubKey(byte[] pubKey) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.findKeyFromPubKey(pubKey);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    @Override
    public boolean isPubKeyMine(byte[] pubKey) {
        return findKeyFromPubKey(pubKey) != null;
    }

    /**
     * Locates a redeem data (redeem script and keys) from the keyChainGroup given the hash of the script.
     * Returns RedeemData object or null if no such data was found.
     */
    @Nullable
    @Override
    public RedeemData findRedeemDataFromScriptHash(byte[] payToScriptHash) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.findRedeemDataFromScriptHash(payToScriptHash);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    @Override
    public boolean isPayToScriptHashMine(byte[] payToScriptHash) {
        return findRedeemDataFromScriptHash(payToScriptHash) != null;
    }

    /**
     * Marks all keys used in the transaction output as used in the wallet.
     * See {@link DeterministicKeyChain#markKeyAsUsed(DeterministicKey)} for more info on this.
     */
    private void markKeysAsUsed(Transaction tx) {
        keyChainGroupLock.lock();
        try {
            for (TransactionOutput o : tx.getOutputs()) {
                try {
                    Script script = o.getScriptPubKey();
                    if (ScriptPattern.isP2PK(script)) {
                        byte[] pubkey = ScriptPattern.extractKeyFromP2PK(script);
                        keyChainGroup.markPubKeyAsUsed(pubkey);
                    } else if (ScriptPattern.isP2PKH(script)) {
                        byte[] pubkeyHash = ScriptPattern.extractHashFromP2PKH(script);
                        keyChainGroup.markPubKeyHashAsUsed(pubkeyHash);
                    } else if (ScriptPattern.isP2SH(script)) {
                        LegacyAddress a = LegacyAddress.fromScriptHash(network(),
                                ScriptPattern.extractHashFromP2SH(script));
                        keyChainGroup.markP2SHAddressAsUsed(a);
                    } else if (ScriptPattern.isP2WH(script)) {
                        byte[] pubkeyHash = ScriptPattern.extractHashFromP2WH(script);
                        keyChainGroup.markPubKeyHashAsUsed(pubkeyHash);
                    }
                } catch (ScriptException e) {
                    // Just means we didn't understand the output of this transaction: ignore it.
                    log.info("Could not parse tx output script: {}", e.toString());
                }
            }
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns the immutable seed for the current active HD chain.
     * @throws ECKey.MissingPrivateKeyException if the seed is unavailable (watching wallet)
     */
    public DeterministicSeed getKeyChainSeed() {
        keyChainGroupLock.lock();
        try {
            DeterministicSeed seed = keyChainGroup.getActiveKeyChain().getSeed();
            if (seed == null)
                throw new ECKey.MissingPrivateKeyException();
            return seed;
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns a key for the given HD path, assuming it's already been derived. You normally shouldn't use this:
     * use currentReceiveKey/freshReceiveKey instead.
     */
    public DeterministicKey getKeyByPath(HDPath.HDPartialPath path) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.getActiveKeyChain().getKeyByPath(path, false);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Convenience wrapper around {@link Wallet#encrypt(KeyCrypter,
     * AesKey)} which uses the default Scrypt key derivation algorithm and
     * parameters to derive a key from the given password.
     */
    public void encrypt(CharSequence password) {
        keyChainGroupLock.lock();
        try {
            final KeyCrypterScrypt scrypt = new KeyCrypterScrypt();
            keyChainGroup.encrypt(scrypt, scrypt.deriveKey(password));
        } finally {
            keyChainGroupLock.unlock();
        }
        saveNow();
    }

    /**
     * Encrypt the wallet using the KeyCrypter and the AES key. A good default KeyCrypter to use is
     * {@link KeyCrypterScrypt}.
     *
     * @param keyCrypter The KeyCrypter that specifies how to encrypt/ decrypt a key
     * @param aesKey AES key to use (normally created using KeyCrypter#deriveKey and cached as it is time consuming to create from a password)
     * @throws KeyCrypterException Thrown if the wallet encryption fails. If so, the wallet state is unchanged.
     */
    public void encrypt(KeyCrypter keyCrypter, AesKey aesKey) {
        keyChainGroupLock.lock();
        try {
            keyChainGroup.encrypt(keyCrypter, aesKey);
        } finally {
            keyChainGroupLock.unlock();
        }
        saveNow();
    }

    /**
     * Decrypt the wallet with the wallets keyCrypter and password.
     * @throws BadWalletEncryptionKeyException Thrown if the given password is wrong. If so, the wallet state is unchanged.
     * @throws KeyCrypterException Thrown if the wallet decryption fails. If so, the wallet state is unchanged.
     */
    public void decrypt(CharSequence password) throws BadWalletEncryptionKeyException {
        keyChainGroupLock.lock();
        try {
            final KeyCrypter crypter = keyChainGroup.getKeyCrypter();
            checkState(crypter != null, () ->
                    "not encrypted");
            keyChainGroup.decrypt(crypter.deriveKey(password));
        } catch (KeyCrypterException.InvalidCipherText | KeyCrypterException.PublicPrivateMismatch e) {
            throw new BadWalletEncryptionKeyException(e);
        } finally {
            keyChainGroupLock.unlock();
        }
        saveNow();
    }

    /**
     * Decrypt the wallet with the wallets keyCrypter and AES key.
     *
     * @param aesKey AES key to use (normally created using KeyCrypter#deriveKey and cached as it is time consuming to create from a password)
     * @throws BadWalletEncryptionKeyException Thrown if the given aesKey is wrong. If so, the wallet state is unchanged.
     * @throws KeyCrypterException Thrown if the wallet decryption fails. If so, the wallet state is unchanged.
     */
    public void decrypt(AesKey aesKey) throws BadWalletEncryptionKeyException {
        keyChainGroupLock.lock();
        try {
            keyChainGroup.decrypt(aesKey);
        } catch (KeyCrypterException.InvalidCipherText | KeyCrypterException.PublicPrivateMismatch e) {
            throw new BadWalletEncryptionKeyException(e);
        } finally {
            keyChainGroupLock.unlock();
        }
        saveNow();
    }

    /**
     *  Check whether the password can decrypt the first key in the wallet.
     *  This can be used to check the validity of an entered password.
     *
     *  @return boolean true if password supplied can decrypt the first private key in the wallet, false otherwise.
     *  @throws IllegalStateException if the wallet is not encrypted.
     */
    public boolean checkPassword(CharSequence password) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.checkPassword(password);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     *  Check whether the AES key can decrypt the first encrypted key in the wallet.
     *
     *  @return boolean true if AES key supplied can decrypt the first encrypted private key in the wallet, false otherwise.
     */
    public boolean checkAESKey(AesKey aesKey) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.checkAESKey(aesKey);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Get the wallet's KeyCrypter, or null if the wallet is not encrypted.
     * (Used in encrypting/ decrypting an ECKey).
     */
    @Nullable
    public KeyCrypter getKeyCrypter() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.getKeyCrypter();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Get the type of encryption used for this wallet.
     *
     * (This is a convenience method - the encryption type is actually stored in the keyCrypter).
     */
    public EncryptionType getEncryptionType() {
        keyChainGroupLock.lock();
        try {
            KeyCrypter crypter = keyChainGroup.getKeyCrypter();
            if (crypter != null)
                return crypter.getUnderstoodEncryptionType();
            else
                return EncryptionType.UNENCRYPTED;
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** Returns true if the wallet is encrypted using any scheme, false if not. */
    public boolean isEncrypted() {
        return getEncryptionType() != EncryptionType.UNENCRYPTED;
    }

    /**
     * Changes wallet encryption password, this is atomic operation.
     * @throws BadWalletEncryptionKeyException Thrown if the given currentPassword is wrong. If so, the wallet state is unchanged.
     * @throws KeyCrypterException Thrown if the wallet decryption fails. If so, the wallet state is unchanged.
     */
    public void changeEncryptionPassword(CharSequence currentPassword, CharSequence newPassword) throws BadWalletEncryptionKeyException {
        keyChainGroupLock.lock();
        try {
            decrypt(currentPassword);
            encrypt(newPassword);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Changes wallet AES encryption key, this is atomic operation.
     * @throws BadWalletEncryptionKeyException Thrown if the given currentAesKey is wrong. If so, the wallet state is unchanged.
     * @throws KeyCrypterException Thrown if the wallet decryption fails. If so, the wallet state is unchanged.
     */
    public void changeEncryptionKey(KeyCrypter keyCrypter, AesKey currentAesKey, AesKey newAesKey) throws BadWalletEncryptionKeyException {
        keyChainGroupLock.lock();
        try {
            decrypt(currentAesKey);
            encrypt(keyCrypter, newAesKey);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    //endregion

    // ***************************************************************************************************************

    //region Serialization support

    @Deprecated
    public List<Protos.Key> serializeKeyChainGroupToProtobuf() {
        return serializeKeyChainGroupToProtobufInternal();
    }

    /** Internal use only. */
    List<Protos.Key> serializeKeyChainGroupToProtobufInternal() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.serializeToProtobuf();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Saves the wallet first to the given temporary file, then renames to the destination file. This is done to make
     * the save an atomic operation.
     *
     * @param tempFile temporary file to use for saving the wallet
     * @param destFile file to save the wallet to
     * @throws FileNotFoundException if directory doesn't exist
     * @throws IOException           if an error occurs while saving
     */
    public void saveToFile(File tempFile, File destFile) throws IOException {
        File tempParentFile = tempFile.getAbsoluteFile().getParentFile();
        if (!tempParentFile.exists()) {
            throw new FileNotFoundException(tempParentFile.getPath() + " (wallet directory not found)");
        }
        File destParentFile = destFile.getAbsoluteFile().getParentFile();
        if (!destParentFile.exists()) {
            throw new FileNotFoundException(destParentFile.getPath() + " (wallet directory not found)");
        }
        FileOutputStream stream = null;
        lock.lock();
        try {
            stream = new FileOutputStream(tempFile);
            saveToFileStream(stream);
            // Attempt to force the bits to hit the disk. In reality the OS or hard disk itself may still decide
            // to not write through to physical media for at least a few seconds, but this is the best we can do.
            stream.flush();
            stream.getFD().sync();
            stream.close();
            stream = null;
            if (PlatformUtils.isWindows()) {
                // Work around an issue on Windows whereby you can't rename over existing files.
                File canonical = destFile.getCanonicalFile();
                if (canonical.exists() && !canonical.delete())
                    throw new IOException("Failed to delete canonical wallet file for replacement with autosave");
                if (tempFile.renameTo(canonical))
                    return;  // else fall through.
                throw new IOException("Failed to rename " + tempFile + " to " + canonical);
            } else if (!tempFile.renameTo(destFile)) {
                throw new IOException("Failed to rename " + tempFile + " to " + destFile);
            }
        } catch (RuntimeException e) {
            log.error("Failed whilst saving wallet", e);
            throw e;
        } finally {
            lock.unlock();
            if (stream != null) {
                stream.close();
            }
            if (tempFile.exists()) {
                log.warn("Temp file still exists after failed save.");
            }
        }
    }

    /**
     * Uses protobuf serialization to save the wallet to the given file. To learn more about this file format, see
     * {@link WalletProtobufSerializer}. Writes out first to a temporary file in the same directory and then renames
     * once written.
     * @param f File to save wallet
     * @throws FileNotFoundException if directory doesn't exist
     * @throws IOException if an error occurs while saving
     */
    public void saveToFile(File f) throws IOException {
        File directory = f.getAbsoluteFile().getParentFile();
        if (!directory.exists()) {
            throw new FileNotFoundException(directory.getPath() + " (wallet directory not found)");
        }
        File temp = File.createTempFile("wallet", null, directory);
        saveToFile(temp, f);
    }

    /**
     * <p>Whether or not the wallet will ignore pending transactions that fail the selected
     * {@link RiskAnalysis}. By default, if a transaction is considered risky then it won't enter the wallet
     * and won't trigger any event listeners. If you set this property to true, then all transactions will
     * be allowed in regardless of risk. For example, the {@link DefaultRiskAnalysis} checks for non-finality of
     * transactions.</p>
     *
     * <p>Note that this property is not serialized. You have to set it each time a Wallet object is constructed,
     * even if it's loaded from a protocol buffer.</p>
     */
    public void setAcceptRiskyTransactions(boolean acceptRiskyTransactions) {
        lock.lock();
        try {
            this.acceptRiskyTransactions = acceptRiskyTransactions;
        } finally {
            lock.unlock();
        }
    }

    /**
     * See {@link Wallet#setAcceptRiskyTransactions(boolean)} for an explanation of this property.
     */
    public boolean isAcceptRiskyTransactions() {
        lock.lock();
        try {
            return acceptRiskyTransactions;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets the {@link RiskAnalysis} implementation to use for deciding whether received pending transactions are risky
     * or not. If the analyzer says a transaction is risky, by default it will be dropped. You can customize this
     * behaviour with {@link #setAcceptRiskyTransactions(boolean)}.
     */
    public void setRiskAnalyzer(RiskAnalysis.Analyzer analyzer) {
        lock.lock();
        try {
            this.riskAnalyzer = Objects.requireNonNull(analyzer);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the current {@link RiskAnalysis} implementation. The default is {@link DefaultRiskAnalysis}.
     */
    public RiskAnalysis.Analyzer getRiskAnalyzer() {
        lock.lock();
        try {
            return riskAnalyzer;
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>Sets up the wallet to auto-save itself to the given file, using temp files with atomic renames to ensure
     * consistency. After connecting to a file, you no longer need to save the wallet manually, it will do it
     * whenever necessary. Protocol buffer serialization will be used.</p>
     *
     * <p>A background thread will be created and the wallet will only be saved to
     * disk every periodically. If no changes have occurred for the given time period, nothing will be written.
     * In this way disk IO can be rate limited. It's a good idea to set this as otherwise the wallet can change very
     * frequently, e.g. if there are a lot of transactions in it or during block sync, and there will be a lot of redundant
     * writes. Note that when a new key is added, that always results in an immediate save regardless of
     * delay. <b>You should still save the wallet manually using {@link Wallet#saveToFile(File)} when your program
     * is about to shut down as the JVM will not wait for the background thread.</b></p>
     *
     * <p>An event listener can be provided. It will be called on a background thread
     * with the wallet locked when an auto-save occurs.</p>
     *
     * @param f The destination file to save to.
     * @param delay How much time to wait until saving the wallet on a background thread.
     * @param eventListener callback to be informed when the auto-save thread does things, or null
     */
    public WalletFiles autosaveToFile(File f, Duration delay, @Nullable WalletFiles.Listener eventListener) {
        lock.lock();
        try {
            checkState(vFileManager == null, () ->
                    "already auto saving this wallet");
            WalletFiles manager = new WalletFiles(this, f, delay);
            if (eventListener != null)
                manager.setListener(eventListener);
            vFileManager = manager;
            return manager;
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>
     * Disables auto-saving, after it had been enabled with
     * {@link Wallet#autosaveToFile(File, Duration, WalletFiles.Listener)}
     * before. This method blocks until finished.
     * </p>
     */
    public void shutdownAutosaveAndWait() {
        lock.lock();
        try {
            WalletFiles files = vFileManager;
            vFileManager = null;
            checkState(files != null, () ->
                    "auto saving not enabled");
            files.shutdownAndWait();
        } finally {
            lock.unlock();
        }
    }

    /** Requests an asynchronous save on a background thread */
    protected void saveLater() {
        WalletFiles files = vFileManager;
        if (files != null)
            files.saveLater();
    }

    /** If auto saving is enabled, do an immediate sync write to disk ignoring any delays. */
    protected void saveNow() {
        WalletFiles files = vFileManager;
        if (files != null) {
            try {
                files.saveNow();  // This calls back into saveToFile().
            } catch (IOException e) {
                // Can't really do much at this point, just let the API user know.
                log.error("Failed to save wallet to disk!", e);
                Thread.UncaughtExceptionHandler handler = Threading.uncaughtExceptionHandler;
                if (handler != null)
                    handler.uncaughtException(Thread.currentThread(), e);
            }
        }
    }

    /**
     * Uses protobuf serialization to save the wallet to the given file stream. To learn more about this file format, see
     * {@link WalletProtobufSerializer}.
     */
    public void saveToFileStream(OutputStream f) throws IOException {
        lock.lock();
        try {
            new WalletProtobufSerializer().writeWallet(this, f);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a wallet deserialized from the given file. Extensions previously saved with the wallet can be
     * deserialized by calling @{@link WalletExtension#deserializeWalletExtension(Wallet, byte[])}}
     *
     * @param file the wallet file to read
     * @param walletExtensions extensions possibly added to the wallet.
     * @return The Wallet
     * @throws UnreadableWalletException if there was a problem loading or parsing the file
     */
    public static Wallet loadFromFile(File file, @Nullable WalletExtension... walletExtensions) throws UnreadableWalletException {
        return loadFromFile(file, WalletProtobufSerializer.WalletFactory.DEFAULT, false, false, walletExtensions);
    }

    /**
     * Returns a wallet deserialized from the given file. Extensions previously saved with the wallet can be
     * deserialized by calling @{@link WalletExtension#deserializeWalletExtension(Wallet, byte[])}}
     *
     * @param file the wallet file to read
     * @param factory wallet factory
     * @param forceReset force a reset of the wallet
     * @param ignoreMandatoryExtensions if true, mandatory extensions will be ignored instead of causing load errors (not recommended)
     * @param walletExtensions extensions possibly added to the wallet.
     * @return The Wallet
     * @throws UnreadableWalletException if there was a problem loading or parsing the file
     */
    public static Wallet loadFromFile(File file, WalletProtobufSerializer.WalletFactory factory, boolean forceReset, boolean ignoreMandatoryExtensions, @Nullable WalletExtension... walletExtensions) throws UnreadableWalletException {
        try (FileInputStream stream = new FileInputStream(file)) {
            return loadFromFileStream(stream, factory, forceReset, ignoreMandatoryExtensions, walletExtensions);
        } catch (IOException e) {
            throw new UnreadableWalletException("Could not open file", e);
        }
    }

    /**
     * Returns if this wallet is structurally consistent, so e.g. no duplicate transactions. First inconsistency and a
     * dump of the wallet will be logged.
     */
    public boolean isConsistent() {
        try {
            isConsistentOrThrow();
            return true;
        } catch (IllegalStateException x) {
            log.error(x.getMessage());
            try {
                log.error(toString());
            } catch (RuntimeException x2) {
                log.error("Printing inconsistent wallet failed", x2);
            }
            return false;
        }
    }

    /**
     * Variant of {@link Wallet#isConsistent()} that throws an {@link IllegalStateException} describing the first
     * inconsistency.
     */
    public void isConsistentOrThrow() throws IllegalStateException {
        lock.lock();
        try {
            Set<Transaction> transactions = getTransactions(true);

            Set<Sha256Hash> hashes = new HashSet<>();
            for (Transaction tx : transactions) {
                hashes.add(tx.getTxId());
            }

            int size1 = transactions.size();
            if (size1 != hashes.size()) {
                throw new IllegalStateException("Two transactions with same hash");
            }

            int size2 = unspent.size() + spent.size() + pending.size() + dead.size();
            if (size1 != size2) {
                throw new IllegalStateException("Inconsistent wallet sizes: " + size1 + ", " + size2);
            }

            for (Transaction tx : unspent.values()) {
                if (!isTxConsistent(tx, false)) {
                    throw new IllegalStateException("Inconsistent unspent tx: " + tx.getTxId());
                }
            }

            for (Transaction tx : spent.values()) {
                if (!isTxConsistent(tx, true)) {
                    throw new IllegalStateException("Inconsistent spent tx: " + tx.getTxId());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /*
     * If isSpent - check that all my outputs spent, otherwise check that there at least
     * one unspent.
     */
    // For testing only
    boolean isTxConsistent(final Transaction tx, final boolean isSpent) {
        boolean isActuallySpent = true;
        for (TransactionOutput o : tx.getOutputs()) {
            if (o.isAvailableForSpending()) {
                if (o.isMineOrWatched(this)) isActuallySpent = false;
                if (o.getSpentBy() != null) {
                    log.error("isAvailableForSpending != spentBy");
                    return false;
                }
            } else {
                if (o.getSpentBy() == null) {
                    log.error("isAvailableForSpending != spentBy");
                    return false;
                }
            }
        }
        return isActuallySpent == isSpent;
    }

    /**
     * Returns a wallet deserialized from the given input stream and wallet extensions.
     * @param stream An open InputStream containing a ProtoBuf Wallet
     * @param walletExtensions extensions possibly added to the wallet.
     * @return The Wallet
     * @throws UnreadableWalletException if there was a problem reading or parsing the stream
     */
    public static Wallet loadFromFileStream(InputStream stream, @Nullable WalletExtension... walletExtensions) throws UnreadableWalletException {
        return loadFromFileStream(stream, WalletProtobufSerializer.WalletFactory.DEFAULT, false, false, walletExtensions);
    }

    /**
     * Returns a wallet deserialized from the given input stream and wallet extensions.
     * @param stream An open InputStream containing a ProtoBuf Wallet
     * @param factory wallet factory
     * @param forceReset if true, configure wallet to replay transactions from the blockchain
     * @param ignoreMandatoryExtensions if true, mandatory extensions will be ignored instead of causing load errors (not recommended)
     * @param walletExtensions extensions possibly added to the wallet.
     * @return The Wallet
     * @throws UnreadableWalletException if there was a problem reading or parsing the stream
     */
    public static Wallet loadFromFileStream(InputStream stream,  WalletProtobufSerializer.WalletFactory factory, boolean forceReset, boolean ignoreMandatoryExtensions, @Nullable WalletExtension... walletExtensions) throws UnreadableWalletException {
        WalletProtobufSerializer loader = new WalletProtobufSerializer(factory);
        if (ignoreMandatoryExtensions) {
            loader.setRequireMandatoryExtensions(false);
        }
        Wallet wallet = loader.readWallet(stream, forceReset, walletExtensions);
        if (!wallet.isConsistent()) {
            log.error("Loaded an inconsistent wallet");
        }
        return wallet;
    }

    //endregion

    // ***************************************************************************************************************

    //region Inbound transaction reception and processing

    /**
     * Called by the {@link BlockChain} when we receive a new filtered block that contains a transactions previously
     * received by a call to {@link #receivePending}.<p>
     *
     * This is necessary for the internal book-keeping Wallet does. When a transaction is received that sends us
     * coins it is added to a pool so we can use it later to create spends. When a transaction is received that
     * consumes outputs they are marked as spent so they won't be used in future.<p>
     *
     * A transaction that spends our own coins can be received either because a spend we created was accepted by the
     * network and thus made it into a block, or because our keys are being shared between multiple instances and
     * some other node spent the coins instead. We still have to know about that to avoid accidentally trying to
     * double spend.<p>
     *
     * A transaction may be received multiple times if is included into blocks in parallel chains. The blockType
     * parameter describes whether the containing block is on the main/best chain or whether it's on a presently
     * inactive side chain. We must still record these transactions and the blocks they appear in because a future
     * block might change which chain is best causing a reorganize. A re-org can totally change our balance!
     */
    @Override
    public boolean notifyTransactionIsInBlock(Sha256Hash txHash, StoredBlock block,
                                              BlockChain.NewBlockType blockType,
                                              int relativityOffset) throws VerificationException {
        lock.lock();
        try {
            Transaction tx = transactions.get(txHash);
            if (tx == null) {
                tx = riskDropped.get(txHash);
                if (tx != null) {
                    // If this happens our risk analysis is probably wrong and should be improved.
                    log.info("Risk analysis dropped tx {} but was included in block anyway", tx.getTxId());
                } else {
                    // False positive that was broadcast to us and ignored by us because it was irrelevant to our keys.
                    return false;
                }
            }
            receive(tx, block, blockType, relativityOffset);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>Called when we have found a transaction (via network broadcast or otherwise) that is relevant to this wallet
     * and want to record it. Note that we <b>cannot verify these transactions at all</b>, they may spend fictional
     * coins or be otherwise invalid. They are useful to inform the user about coins they can expect to receive soon,
     * and if you trust the sender of the transaction you can choose to assume they are in fact valid and will not
     * be double spent as an optimization.</p>
     *
     * <p>This is the same as {@link Wallet#receivePending(Transaction, List)} but allows you to override the
     * {@link Wallet#isPendingTransactionRelevant(Transaction)} sanity-check to keep track of transactions that are not
     * spendable or spend our coins. This can be useful when you want to keep track of transaction confidence on
     * arbitrary transactions. Note that transactions added in this way will still be relayed to peers and appear in
     * transaction lists like any other pending transaction (even when not relevant).</p>
     */
    public void receivePending(Transaction tx, @Nullable List<Transaction> dependencies, boolean overrideIsRelevant) throws VerificationException {
        // Can run in a peer thread. This method will only be called if a prior call to isPendingTransactionRelevant
        // returned true, so we already know by this point that it sends coins to or from our wallet, or is a double
        // spend against one of our other pending transactions.
        lock.lock();
        try {
            Transaction.verify(network, tx);
            // Ignore it if we already know about this transaction. Receiving a pending transaction never moves it
            // between pools.
            EnumSet<Pool> containingPools = getContainingPools(tx);
            if (!containingPools.equals(EnumSet.noneOf(Pool.class))) {
                log.debug("Received tx we already saw in a block or created ourselves: " + tx.getTxId());
                return;
            }
            // Repeat the check of relevancy here, even though the caller may have already done so - this is to avoid
            // race conditions where receivePending may be being called in parallel.
            if (!overrideIsRelevant && !isPendingTransactionRelevant(tx))
                return;
            if (isTransactionRisky(tx, dependencies) && !acceptRiskyTransactions) {
                // isTransactionRisky already logged the reason.

                // Clone transaction to avoid multiple wallets pointing to the same transaction. This can happen when
                // two wallets depend on the same transaction.
                Transaction cloneTx = params.getDefaultSerializer().makeTransaction(ByteBuffer.wrap(tx.serialize()));
                cloneTx.setPurpose(tx.getPurpose());
                Optional<Instant> updateTime = tx.updateTime();
                if (updateTime.isPresent())
                    cloneTx.setUpdateTime(updateTime.get());
                else
                    cloneTx.clearUpdateTime();

                riskDropped.put(cloneTx.getTxId(), cloneTx);
                log.warn("There are now {} risk dropped transactions being kept in memory", riskDropped.size());
                return;
            }
            Coin valueSentToMe = tx.getValueSentToMe(this);
            Coin valueSentFromMe = tx.getValueSentFromMe(this);
            if (log.isInfoEnabled())
                log.info("Received a pending transaction {} that spends {} from our own wallet, and sends us {}",
                        tx.getTxId(), valueSentFromMe.toFriendlyString(), valueSentToMe.toFriendlyString());
            if (getConfidence(tx).getSource().equals(TransactionConfidence.Source.UNKNOWN)) {
                log.warn("Wallet received transaction with an unknown source. Consider tagging it!");
            }

            // Clone transaction to avoid multiple wallets pointing to the same transaction. This can happen when
            // two wallets depend on the same transaction.
            Transaction cloneTx = params.getDefaultSerializer().makeTransaction(ByteBuffer.wrap(tx.serialize()));
            cloneTx.setPurpose(tx.getPurpose());
            Optional<Instant> updateTime = tx.updateTime();
            if (updateTime.isPresent())
                cloneTx.setUpdateTime(updateTime.get());
            else
                cloneTx.clearUpdateTime();

            // If this tx spends any of our unspent outputs, mark them as spent now, then add to the pending pool. This
            // ensures that if some other client that has our keys broadcasts a spend we stay in sync. Also updates the
            // timestamp on the transaction and registers/runs event listeners.
            commitTx(cloneTx);
        } finally {
            lock.unlock();
        }
        // maybeRotateKeys() will ignore pending transactions so we don't bother calling it here (see the comments
        // in that function for an explanation of why).
    }

    /**
     * Given a transaction and an optional list of dependencies (recursive/flattened), returns true if the given
     * transaction would be rejected by the analyzer, or false otherwise. The result of this call is independent
     * of the value of {@link #isAcceptRiskyTransactions()}. Risky transactions yield a logged warning. If you
     * want to know the reason why a transaction is risky, create an instance of the {@link RiskAnalysis} yourself
     * using the factory returned by {@link #getRiskAnalyzer()} and use it directly.
     */
    public boolean isTransactionRisky(Transaction tx, @Nullable List<Transaction> dependencies) {
        lock.lock();
        try {
            if (dependencies == null)
                dependencies = Collections.emptyList();
            RiskAnalysis analysis = riskAnalyzer.create(this, tx, dependencies);
            RiskAnalysis.Result result = analysis.analyze();
            if (result != RiskAnalysis.Result.OK) {
                log.warn("Pending transaction was considered risky: {}\n{}", analysis, tx);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>Called when we have found a transaction (via network broadcast or otherwise) that is relevant to this wallet
     * and want to record it. Note that we <b>cannot verify these transactions at all</b>, they may spend fictional
     * coins or be otherwise invalid. They are useful to inform the user about coins they can expect to receive soon,
     * and if you trust the sender of the transaction you can choose to assume they are in fact valid and will not
     * be double spent as an optimization.</p>
     *
     * <p>Before this method is called, {@link Wallet#isPendingTransactionRelevant(Transaction)} should have been
     * called to decide whether the wallet cares about the transaction - if it does, then this method expects the
     * transaction and any dependencies it has which are still in the memory pool.</p>
     */
    public void receivePending(Transaction tx, @Nullable List<Transaction> dependencies) throws VerificationException {
        receivePending(tx, dependencies, false);
    }

    /**
     * This method is used by a {@link Peer} to find out if a transaction that has been announced is interesting,
     * that is, whether we should bother downloading its dependencies and exploring the transaction to decide how
     * risky it is. If this method returns true then {@link Wallet#receivePending(Transaction, List)}
     * will soon be called with the transactions dependencies as well.
     */
    public boolean isPendingTransactionRelevant(Transaction tx) throws ScriptException {
        lock.lock();
        try {
            // Ignore it if we already know about this transaction. Receiving a pending transaction never moves it
            // between pools.
            EnumSet<Pool> containingPools = getContainingPools(tx);
            if (!containingPools.equals(EnumSet.noneOf(Pool.class))) {
                log.debug("Received tx we already saw in a block or created ourselves: " + tx.getTxId());
                return false;
            }
            // We only care about transactions that:
            //   - Send us coins
            //   - Spend our coins
            //   - Double spend a tx in our wallet
            if (!isTransactionRelevant(tx)) {
                log.debug("Received tx that isn't relevant to this wallet, discarding.");
                return false;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>Returns true if the given transaction sends coins to any of our keys, or has inputs spending any of our outputs,
     * and also returns true if tx has inputs that are spending outputs which are
     * not ours but which are spent by pending transactions.</p>
     *
     * <p>Note that if the tx has inputs containing one of our keys, but the connected transaction is not in the wallet,
     * it will not be considered relevant.</p>
     */
    public boolean isTransactionRelevant(Transaction tx) throws ScriptException {
        lock.lock();
        try {
            return tx.getValueSentFromMe(this).signum() > 0 ||
                   tx.getValueSentToMe(this).signum() > 0 ||
                   !findDoubleSpendsAgainst(tx, transactions).isEmpty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Determine if a transaction is <i>mature</i>. A coinbase transaction is <i>mature</i> if it has been confirmed at least
     * {@link NetworkParameters#getSpendableCoinbaseDepth()} times. On {@link BitcoinNetwork#MAINNET} this value is {@code 100}.
     * For purposes of this method, non-coinbase transactions are also considered <i>mature</i>.
     * @param tx the transaction to evaluate
     * @return {@code true} if it is a mature coinbase transaction or if it is not a coinbase transaction
     */
    public boolean isTransactionMature(Transaction tx) {
        return !tx.isCoinBase() ||
                getConfidence(tx).getDepthInBlocks() >= params.getSpendableCoinbaseDepth();
    }

    /**
     * Finds transactions in the specified candidates that double spend "tx". Not a general check, but it can work even if
     * the double spent inputs are not ours.
     * @return The set of transactions that double spend "tx".
     */
    private Set<Transaction> findDoubleSpendsAgainst(Transaction tx, Map<Sha256Hash, Transaction> candidates) {
        checkState(lock.isHeldByCurrentThread());
        if (tx.isCoinBase()) return new HashSet<>();
        // Compile a set of outpoints that are spent by tx.
        HashSet<TransactionOutPoint> outpoints = new HashSet<>();
        for (TransactionInput input : tx.getInputs()) {
            outpoints.add(input.getOutpoint());
        }
        // Now for each pending transaction, see if it shares any outpoints with this tx.
        Set<Transaction> doubleSpendTxns = new HashSet<>();
        for (Transaction p : candidates.values()) {
            if (p.equals(tx))
                continue;
            for (TransactionInput input : p.getInputs()) {
                // This relies on the fact that TransactionOutPoint equality is defined at the protocol not object
                // level - outpoints from two different inputs that point to the same output compare the same.
                TransactionOutPoint outpoint = input.getOutpoint();
                if (outpoints.contains(outpoint)) {
                    // It does, it's a double spend against the candidates, which makes it relevant.
                    doubleSpendTxns.add(p);
                }
            }
        }
        return doubleSpendTxns;
    }

    /**
     * Adds to txSet all the txns in txPool spending outputs of txns in txSet,
     * and all txns spending the outputs of those txns, recursively.
     */
    void addTransactionsDependingOn(Set<Transaction> txSet, Set<Transaction> txPool) {
        Map<Sha256Hash, Transaction> txQueue = new LinkedHashMap<>();
        for (Transaction tx : txSet) {
            txQueue.put(tx.getTxId(), tx);
        }
        while(!txQueue.isEmpty()) {
            Transaction tx = txQueue.remove(txQueue.keySet().iterator().next());
            for (Transaction anotherTx : txPool) {
                if (anotherTx.equals(tx)) continue;
                for (TransactionInput input : anotherTx.getInputs()) {
                    if (input.getOutpoint().hash().equals(tx.getTxId())) {
                        if (txQueue.get(anotherTx.getTxId()) == null) {
                            txQueue.put(anotherTx.getTxId(), anotherTx);
                            txSet.add(anotherTx);
                        }
                    }
                }
            }
        }
    }

    /**
     * Called by the {@link BlockChain} when we receive a new block that sends coins to one of our addresses or
     * spends coins from one of our addresses (note that a single transaction can do both).<p>
     *
     * This is necessary for the internal book-keeping Wallet does. When a transaction is received that sends us
     * coins it is added to a pool so we can use it later to create spends. When a transaction is received that
     * consumes outputs they are marked as spent so they won't be used in future.<p>
     *
     * A transaction that spends our own coins can be received either because a spend we created was accepted by the
     * network and thus made it into a block, or because our keys are being shared between multiple instances and
     * some other node spent the coins instead. We still have to know about that to avoid accidentally trying to
     * double spend.<p>
     *
     * A transaction may be received multiple times if is included into blocks in parallel chains. The blockType
     * parameter describes whether the containing block is on the main/best chain or whether it's on a presently
     * inactive side chain. We must still record these transactions and the blocks they appear in because a future
     * block might change which chain is best causing a reorganize. A re-org can totally change our balance!
     */
    @Override
    public void receiveFromBlock(Transaction tx, StoredBlock block,
                                 BlockChain.NewBlockType blockType,
                                 int relativityOffset) throws VerificationException {
        lock.lock();
        try {
            if (!isTransactionRelevant(tx))
                return;
            receive(tx, block, blockType, relativityOffset);
        } finally {
            lock.unlock();
        }
    }

    // Whether to do a saveNow or saveLater when we are notified of the next best block.
    private boolean hardSaveOnNextBlock = false;

    private void receive(Transaction tx, StoredBlock block, BlockChain.NewBlockType blockType,
                         int relativityOffset) throws VerificationException {
        // Runs in a peer thread.
        checkState(lock.isHeldByCurrentThread());

        Coin prevBalance = getBalance();
        Sha256Hash txHash = tx.getTxId();
        boolean bestChain = blockType == BlockChain.NewBlockType.BEST_CHAIN;
        boolean sideChain = blockType == BlockChain.NewBlockType.SIDE_CHAIN;

        Coin valueSentFromMe = tx.getValueSentFromMe(this);
        Coin valueSentToMe = tx.getValueSentToMe(this);
        Coin valueDifference = valueSentToMe.subtract(valueSentFromMe);

        log.info("Received tx{} for {}: {} [{}] in block {}", sideChain ? " on a side chain" : "",
                valueDifference.toFriendlyString(), tx.getTxId(), relativityOffset,
                block != null ? block.getHeader().getHash() : "(unit test)");

        // Inform the key chains that the issued keys were observed in a transaction, so they know to
        // calculate more keys for the next Bloom filters.
        markKeysAsUsed(tx);

        onWalletChangedSuppressions++;

        // If this transaction is already in the wallet we may need to move it into a different pool. At the very
        // least we need to ensure we're manipulating the canonical object rather than a duplicate.
        {
            Transaction tmp = transactions.get(tx.getTxId());
            if (tmp != null)
                tx = tmp;
        }

        boolean wasPending = pending.remove(txHash) != null;
        if (wasPending)
            log.info("  <-pending");

        if (bestChain) {
            boolean wasDead = dead.remove(txHash) != null;
            if (wasDead)
                log.info("  <-dead");
            if (wasPending) {
                // Was pending and is now confirmed. Disconnect the outputs in case we spent any already: they will be
                // re-connected by processTxFromBestChain below.
                for (TransactionOutput output : tx.getOutputs()) {
                    final TransactionInput spentBy = output.getSpentBy();
                    if (spentBy != null) {
                        checkState(myUnspents.add(output));
                        spentBy.disconnect();
                    }
                }
            }
            processTxFromBestChain(tx, wasPending || wasDead);
        } else {
            checkState(sideChain);
            // Transactions that appear in a side chain will have that appearance recorded below - we assume that
            // some miners are also trying to include the transaction into the current best chain too, so let's treat
            // it as pending, except we don't need to do any risk analysis on it.
            if (wasPending) {
                // Just put it back in without touching the connections or confidence.
                addWalletTransaction(Pool.PENDING, tx);
                log.info("  ->pending");
            } else {
                // Ignore the case where a tx appears on a side chain at the same time as the best chain (this is
                // quite normal and expected).
                Sha256Hash hash = tx.getTxId();
                if (!unspent.containsKey(hash) && !spent.containsKey(hash) && !dead.containsKey(hash)) {
                    // Otherwise put it (possibly back) into pending.
                    // Committing it updates the spent flags and inserts into the pool as well.
                    commitTx(tx);
                }
            }
        }

        if (block != null) {
            // Mark the tx as appearing in this block so we can find it later after a re-org. This also tells the tx
            // confidence object about the block and sets its depth appropriately.
            tx.setBlockAppearance(block, bestChain, relativityOffset);
            if (bestChain) {
                // Don't notify this tx of work done in notifyNewBestBlock which will be called immediately after
                // this method has been called by BlockChain for all relevant transactions. Otherwise we'd double
                // count.
                ignoreNextNewBlock.add(txHash);

                // When a tx is received from the best chain, if other txns that spend this tx are IN_CONFLICT,
                // change its confidence to PENDING (Unless they are also spending other txns IN_CONFLICT).
                // Consider dependency chains.
                Set<Transaction> currentTxDependencies = new HashSet<>();
                currentTxDependencies.add(tx);
                addTransactionsDependingOn(currentTxDependencies, getTransactions(true));
                currentTxDependencies.remove(tx);
                List<Transaction> currentTxDependenciesSorted = sortTxnsByDependency(currentTxDependencies);
                for (Transaction txDependency : currentTxDependenciesSorted) {
                    if (getConfidence(txDependency).getConfidenceType().equals(ConfidenceType.IN_CONFLICT)) {
                        if (isNotSpendingTxnsInConfidenceType(txDependency, ConfidenceType.IN_CONFLICT)) {
                            getConfidence(txDependency).setConfidenceType(ConfidenceType.PENDING);
                            confidenceChanged.put(txDependency, TransactionConfidence.Listener.ChangeReason.TYPE);
                        }
                    }
                }
            }
        }

        onWalletChangedSuppressions--;

        // Side chains don't affect confidence.
        if (bestChain) {
            // notifyNewBestBlock will be invoked next and will then call maybeQueueOnWalletChanged for us.
            confidenceChanged.put(tx, TransactionConfidence.Listener.ChangeReason.TYPE);
        } else {
            maybeQueueOnWalletChanged();
        }

        // Inform anyone interested that we have received or sent coins but only if:
        //  - This is not due to a re-org.
        //  - The coins appeared on the best chain.
        //  - We did in fact receive some new money.
        //  - We have not already informed the user about the coins when we received the tx broadcast, or for our
        //    own spends. If users want to know when a broadcast tx becomes confirmed, they need to use tx confidence
        //    listeners.
        if (!insideReorg && bestChain) {
            Coin newBalance = getBalance();  // This is slow.
            log.info("Balance is now: " + newBalance.toFriendlyString());
            if (!wasPending) {
                int diff = valueDifference.signum();
                // We pick one callback based on the value difference, though a tx can of course both send and receive
                // coins from the wallet.
                if (diff > 0) {
                    queueOnCoinsReceived(tx, prevBalance, newBalance);
                } else if (diff < 0) {
                    queueOnCoinsSent(tx, prevBalance, newBalance);
                }
            }
            checkBalanceFuturesLocked();
        }

        informConfidenceListenersIfNotReorganizing();
        isConsistentOrThrow();
        // Optimization for the case where a block has tons of relevant transactions.
        saveLater();
        hardSaveOnNextBlock = true;
    }

    /** Finds if tx is NOT spending other txns which are in the specified confidence type */
    private boolean isNotSpendingTxnsInConfidenceType(Transaction tx, ConfidenceType confidenceType) {
        for (TransactionInput txInput : tx.getInputs()) {
            Transaction connectedTx = this.getTransaction(txInput.getOutpoint().hash());
            if (connectedTx != null && getConfidence(connectedTx).getConfidenceType().equals(confidenceType)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Creates and returns a new List with the same txns as inputSet
     * but txns are sorted by dependency (a topological sort).
     * If tx B spends tx A, then tx A should be before tx B on the returned List.
     * Several invocations to this method with the same inputSet could result in lists with txns in different order,
     * as there is no guarantee on the order of the returned txns besides what was already stated.
     */
    List<Transaction> sortTxnsByDependency(Set<Transaction> inputSet) {
        List<Transaction> result = new ArrayList<>(inputSet);
        for (int i = 0; i < result.size()-1; i++) {
            boolean txAtISpendsOtherTxInTheList;
            do {
                txAtISpendsOtherTxInTheList = false;
                for (int j = i+1; j < result.size(); j++) {
                    if (spends(result.get(i), result.get(j))) {
                        Transaction transactionAtI = result.remove(i);
                        result.add(j, transactionAtI);
                        txAtISpendsOtherTxInTheList = true;
                        break;
                    }
                }
            } while (txAtISpendsOtherTxInTheList);
        }
        return result;
    }

    /** Finds whether txA spends txB */
    boolean spends(Transaction txA, Transaction txB) {
        for (TransactionInput txInput : txA.getInputs()) {
            if (txInput.getOutpoint().hash().equals(txB.getTxId())) {
                return true;
            }
        }
        return false;
    }

    private void informConfidenceListenersIfNotReorganizing() {
        if (insideReorg)
            return;
        for (Map.Entry<Transaction, TransactionConfidence.Listener.ChangeReason> entry : confidenceChanged.entrySet()) {
            final Transaction tx = entry.getKey();
            getConfidence(tx).queueListeners(entry.getValue());
            queueOnTransactionConfidenceChanged(tx);
        }
        confidenceChanged.clear();
    }

    /**
     * <p>Called by the {@link BlockChain} when a new block on the best chain is seen, AFTER relevant wallet
     * transactions are extracted and sent to us UNLESS the new block caused a re-org, in which case this will
     * not be called (the {@link Wallet#reorganize(StoredBlock, List, List)} method will
     * call this one in that case).</p>
     * <p>Used to update confidence data in each transaction and last seen block hash. Triggers auto saving.
     * Invokes the onWalletChanged event listener if there were any affected transactions.</p>
     */
    @Override
    public void notifyNewBestBlock(StoredBlock block) throws VerificationException {
        // Check to see if this block has been seen before.
        Sha256Hash newBlockHash = block.getHeader().getHash();
        if (newBlockHash.equals(getLastBlockSeenHash()))
            return;
        lock.lock();
        try {
            // Store the new block hash.
            setLastBlockSeenHash(newBlockHash);
            setLastBlockSeenHeight(block.getHeight());
            setLastBlockSeenTime(block.getHeader().time());
            // Notify all the BUILDING transactions of the new block.
            // This is so that they can update their depth.
            Set<Transaction> transactions = getTransactions(true);
            for (Transaction tx : transactions) {
                if (ignoreNextNewBlock.contains(tx.getTxId())) {
                    // tx was already processed in receive() due to it appearing in this block, so we don't want to
                    // increment the tx confidence depth twice, it'd result in miscounting.
                    ignoreNextNewBlock.remove(tx.getTxId());
                } else {
                    TransactionConfidence confidence = getConfidence(tx);
                    if (confidence.getConfidenceType() == ConfidenceType.BUILDING) {
                        // Erase the set of seen peers once the tx is so deep that it seems unlikely to ever go
                        // pending again. We could clear this data the moment a tx is seen in the block chain, but
                        // in cases where the chain re-orgs, this would mean that wallets would perceive a newly
                        // pending tx has zero confidence at all, which would not be right: we expect it to be
                        // included once again. We could have a separate was-in-chain-and-now-isn't confidence type
                        // but this way is backwards compatible with existing software, and the new state probably
                        // wouldn't mean anything different to just remembering peers anyway.
                        if (confidence.incrementDepthInBlocks() > Context.getOrCreate().getEventHorizon())
                            confidence.clearBroadcastBy();
                        confidenceChanged.put(tx, TransactionConfidence.Listener.ChangeReason.DEPTH);
                    }
                }
            }

            informConfidenceListenersIfNotReorganizing();
            maybeQueueOnWalletChanged();

            if (hardSaveOnNextBlock) {
                saveNow();
                hardSaveOnNextBlock = false;
            } else {
                // Coalesce writes to avoid throttling on disk access when catching up with the chain.
                saveLater();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Handle when a transaction becomes newly active on the best chain, either due to receiving a new block or a
     * re-org. Places the tx into the right pool, handles coinbase transactions, handles double-spends and so on.
     */
    private void processTxFromBestChain(Transaction tx, boolean forceAddToPool) throws VerificationException {
        checkState(lock.isHeldByCurrentThread());
        checkState(!pending.containsKey(tx.getTxId()));

        // This TX may spend our existing outputs even though it was not pending. This can happen in unit
        // tests, if keys are moved between wallets, if we're catching up to the chain given only a set of keys,
        // or if a dead coinbase transaction has moved back onto the best chain.
        boolean isDeadCoinbase = tx.isCoinBase() && dead.containsKey(tx.getTxId());
        if (isDeadCoinbase) {
            // There is a dead coinbase tx being received on the best chain. A coinbase tx is made dead when it moves
            // to a side chain but it can be switched back on a reorg and resurrected back to spent or unspent.
            // So take it out of the dead pool. Note that we don't resurrect dependent transactions here, even though
            // we could. Bitcoin Core nodes on the network have deleted the dependent transactions from their mempools
            // entirely by this point. We could and maybe should rebroadcast them so the network remembers and tries
            // to confirm them again. But this is a deeply unusual edge case that due to the maturity rule should never
            // happen in practice, thus for simplicities sake we ignore it here.
            log.info("  coinbase tx <-dead: confidence {}", tx.getTxId(),
                    getConfidence(tx).getConfidenceType().name());
            dead.remove(tx.getTxId());
        }

        // Update tx and other unspent/pending transactions by connecting inputs/outputs.
        updateForSpends(tx, true);

        // Now make sure it ends up in the right pool. Also, handle the case where this TX is double-spending
        // against our pending transactions. Note that a tx may double spend our pending transactions and also send
        // us money/spend our money.
        boolean hasOutputsToMe = tx.getValueSentToMe(this).signum() > 0;
        boolean hasOutputsFromMe = false;
        if (hasOutputsToMe) {
            // Needs to go into either unspent or spent (if the outputs were already spent by a pending tx).
            if (tx.isEveryOwnedOutputSpent(this)) {
                log.info("  tx {} ->spent (by pending)", tx.getTxId());
                addWalletTransaction(Pool.SPENT, tx);
            } else {
                log.info("  tx {} ->unspent", tx.getTxId());
                addWalletTransaction(Pool.UNSPENT, tx);
            }
        } else if (tx.getValueSentFromMe(this).signum() > 0) {
            hasOutputsFromMe = true;
            // Didn't send us any money, but did spend some. Keep it around for record keeping purposes.
            log.info("  tx {} ->spent", tx.getTxId());
            addWalletTransaction(Pool.SPENT, tx);
        } else if (forceAddToPool) {
            // Was manually added to pending, so we should keep it to notify the user of confidence information
            log.info("  tx {} ->spent (manually added)", tx.getTxId());
            addWalletTransaction(Pool.SPENT, tx);
        }

        // Kill txns in conflict with this tx
        Set<Transaction> doubleSpendTxns = findDoubleSpendsAgainst(tx, pending);
        if (!doubleSpendTxns.isEmpty()) {
            // no need to addTransactionsDependingOn(doubleSpendTxns) because killTxns() already kills dependencies;
            killTxns(doubleSpendTxns, tx);
        }
        if (!hasOutputsToMe
            && !hasOutputsFromMe
            && !forceAddToPool
            && !findDoubleSpendsAgainst(tx, transactions).isEmpty())
        {
            // disconnect irrelevant inputs (otherwise might cause protobuf serialization issue)
            for (TransactionInput input : tx.getInputs()) {
                TransactionOutput output = input.getConnectedOutput();
                if (output != null && !output.isMineOrWatched(this)) {
                    input.disconnect();
                }
            }
        }
    }

    /**
     * <p>Updates the wallet by checking if this TX spends any of our outputs, and marking them as spent if so. If
     * fromChain is true, also checks to see if any pending transaction spends outputs of this transaction and marks
     * the spent flags appropriately.</p>
     *
     * <p>It can be called in two contexts. One is when we receive a transaction on the best chain but it wasn't pending,
     * this most commonly happens when we have a set of keys but the wallet transactions were wiped and we are catching
     * up with the block chain. It can also happen if a block includes a transaction we never saw at broadcast time.
     * If this tx double spends, it takes precedence over our pending transactions and the pending tx goes dead.</p>
     *
     * <p>The other context it can be called is from {@link Wallet#receivePending(Transaction, List)},
     * ie we saw a tx be broadcast or one was submitted directly that spends our own coins. If this tx double spends
     * it does NOT take precedence because the winner will be resolved by the miners - we assume that our version will
     * win, if we are wrong then when a block appears the tx will go dead.</p>
     *
     * @param tx The transaction which is being updated.
     * @param fromChain If true, the tx appeared on the current best chain, if false it was pending.
     */
    private void updateForSpends(Transaction tx, boolean fromChain) throws VerificationException {
        checkState(lock.isHeldByCurrentThread());
        if (fromChain)
            checkState(!pending.containsKey(tx.getTxId()));
        for (TransactionInput input : tx.getInputs()) {
            TransactionInput.ConnectionResult result = input.connect(unspent, TransactionInput.ConnectMode.ABORT_ON_CONFLICT);
            if (result == TransactionInput.ConnectionResult.NO_SUCH_TX) {
                // Not found in the unspent map. Try again with the spent map.
                result = input.connect(spent, TransactionInput.ConnectMode.ABORT_ON_CONFLICT);
                if (result == TransactionInput.ConnectionResult.NO_SUCH_TX) {
                    // Not found in the unspent and spent maps. Try again with the pending map.
                    result = input.connect(pending, TransactionInput.ConnectMode.ABORT_ON_CONFLICT);
                    if (result == TransactionInput.ConnectionResult.NO_SUCH_TX) {
                        // Doesn't spend any of our outputs or is coinbase.
                        continue;
                    }
                }
            }

            TransactionOutput output = Objects.requireNonNull(input.getConnectedOutput());
            if (result == TransactionInput.ConnectionResult.ALREADY_SPENT) {
                if (fromChain) {
                    // Can be:
                    // (1) We already marked this output as spent when we saw the pending transaction (most likely).
                    //     Now it's being confirmed of course, we cannot mark it as spent again.
                    // (2) A double spend from chain: this will be handled later by findDoubleSpendsAgainst()/killTxns().
                    //
                    // In any case, nothing to do here.
                } else {
                    // We saw two pending transactions that double spend each other. We don't know which will win.
                    // This can happen in the case of bad network nodes that mutate transactions. Do a hex dump
                    // so the exact nature of the mutation can be examined.
                    log.warn("Saw two pending transactions double spend each other");
                    log.warn("  offending input is input {}", tx.getInputs().indexOf(input));
                    log.warn("{}: {}", tx.getTxId(), ByteUtils.formatHex(tx.serialize()));
                    Transaction other = output.getSpentBy().getParentTransaction();
                    log.warn("{}: {}", other.getTxId(), ByteUtils.formatHex(other.serialize()));
                }
            } else if (result == TransactionInput.ConnectionResult.SUCCESS) {
                // Otherwise we saw a transaction spend our coins, but we didn't try and spend them ourselves yet.
                // The outputs are already marked as spent by the connect call above, so check if there are any more for
                // us to use. Move if not.
                Transaction connected = Objects.requireNonNull(input.getConnectedTransaction());
                log.info("  marked {} as spent by {}", input.getOutpoint(), tx.getTxId());
                maybeMovePool(connected, "prevtx");
                // Just because it's connected doesn't mean it's actually ours: sometimes we have total visibility.
                if (output.isMineOrWatched(this)) {
                    checkState(myUnspents.remove(output));
                }
            }
        }
        // Now check each output and see if there is a pending transaction which spends it. This shouldn't normally
        // ever occur because we expect transactions to arrive in temporal order, but this assumption can be violated
        // when we receive a pending transaction from the mempool that is relevant to us, which spends coins that we
        // didn't see arrive on the best chain yet. For instance, because of a chain replay or because of our keys were
        // used by another wallet somewhere else. Also, unconfirmed transactions can arrive from the mempool in more or
        // less random order.
        for (Transaction pendingTx : pending.values()) {
            for (TransactionInput input : pendingTx.getInputs()) {
                TransactionInput.ConnectionResult result = input.connect(tx, TransactionInput.ConnectMode.ABORT_ON_CONFLICT);
                if (fromChain) {
                    // This TX is supposed to have just appeared on the best chain, so its outputs should not be marked
                    // as spent yet. If they are, it means something is happening out of order.
                    checkState(result != TransactionInput.ConnectionResult.ALREADY_SPENT);
                }
                if (result == TransactionInput.ConnectionResult.SUCCESS) {
                    log.info("Connected pending tx input {}:{}",
                            pendingTx.getTxId(), pendingTx.getInputs().indexOf(input));
                    // The unspents map might not have it if we never saw this tx until it was included in the chain
                    // and thus becomes spent the moment we become aware of it.
                    if (myUnspents.remove(input.getConnectedOutput()))
                        log.info("Removed from UNSPENTS: {}", input.getConnectedOutput());
                }
            }
        }
        if (!fromChain) {
            maybeMovePool(tx, "pendingtx");
        } else {
            // If the transactions outputs are now all spent, it will be moved into the spent pool by the
            // processTxFromBestChain method.
        }
    }

    // Updates the wallet when a double spend occurs. overridingTx can be null for the case of coinbases
    private void killTxns(Set<Transaction> txnsToKill, @Nullable Transaction overridingTx) {
        LinkedList<Transaction> work = new LinkedList<>(txnsToKill);
        while (!work.isEmpty()) {
            final Transaction tx = work.poll();
            log.warn("TX {} killed{}", tx.getTxId(),
                    overridingTx != null ? " by " + overridingTx.getTxId() : "");
            log.warn("Disconnecting each input and moving connected transactions.");
            // TX could be pending (finney attack), or in unspent/spent (coinbase killed by reorg).
            pending.remove(tx.getTxId());
            unspent.remove(tx.getTxId());
            spent.remove(tx.getTxId());
            addWalletTransaction(Pool.DEAD, tx);
            for (TransactionInput deadInput : tx.getInputs()) {
                Transaction connected = deadInput.getConnectedTransaction();
                if (connected == null) continue;
                if (getConfidence(connected).getConfidenceType() != ConfidenceType.DEAD && deadInput.getConnectedOutput().getSpentBy() != null && deadInput.getConnectedOutput().getSpentBy().equals(deadInput)) {
                    checkState(myUnspents.add(deadInput.getConnectedOutput()));
                    log.info("Added to UNSPENTS: {} in {}", deadInput.getConnectedOutput(), deadInput.getConnectedOutput().getParentTransaction().getTxId());
                }
                deadInput.disconnect();
                maybeMovePool(connected, "kill");
            }
            getConfidence(tx).setOverridingTxId(overridingTx != null ? overridingTx.getTxId() : null);
            confidenceChanged.put(tx, TransactionConfidence.Listener.ChangeReason.TYPE);
            // Now kill any transactions we have that depended on this one.
            for (TransactionOutput deadOutput : tx.getOutputs()) {
                if (myUnspents.remove(deadOutput))
                    log.info("XX Removed from UNSPENTS: {}", deadOutput);
                TransactionInput connected = deadOutput.getSpentBy();
                if (connected == null) continue;
                final Transaction parentTransaction = connected.getParentTransaction();
                log.info("This death invalidated dependent tx {}", parentTransaction.getTxId());
                work.push(parentTransaction);
            }
        }
        if (overridingTx == null)
            return;
        log.warn("Now attempting to connect the inputs of the overriding transaction.");
        for (TransactionInput input : overridingTx.getInputs()) {
            TransactionInput.ConnectionResult result = input.connect(unspent, TransactionInput.ConnectMode.DISCONNECT_ON_CONFLICT);
            if (result == TransactionInput.ConnectionResult.SUCCESS) {
                maybeMovePool(input.getConnectedTransaction(), "kill");
                myUnspents.remove(input.getConnectedOutput());
                log.info("Removing from UNSPENTS: {}", input.getConnectedOutput());
            } else {
                result = input.connect(spent, TransactionInput.ConnectMode.DISCONNECT_ON_CONFLICT);
                if (result == TransactionInput.ConnectionResult.SUCCESS) {
                    maybeMovePool(input.getConnectedTransaction(), "kill");
                    myUnspents.remove(input.getConnectedOutput());
                    log.info("Removing from UNSPENTS: {}", input.getConnectedOutput());
                }
            }
        }
    }

    /**
     * If the transactions outputs are all marked as spent, and it's in the unspent map, move it.
     * If the owned transactions outputs are not all marked as spent, and it's in the spent map, move it.
     */
    private void maybeMovePool(Transaction tx, String context) {
        checkState(lock.isHeldByCurrentThread());
        if (tx.isEveryOwnedOutputSpent(this)) {
            // There's nothing left I can spend in this transaction.
            if (unspent.remove(tx.getTxId()) != null) {
                if (log.isInfoEnabled()) {
                    log.info("  {} {} <-unspent ->spent", tx.getTxId(), context);
                }
                spent.put(tx.getTxId(), tx);
            }
        } else {
            if (spent.remove(tx.getTxId()) != null) {
                if (log.isInfoEnabled()) {
                    log.info("  {} {} <-spent ->unspent", tx.getTxId(), context);
                }
                unspent.put(tx.getTxId(), tx);
            }
        }
    }

    /**
     * Updates the wallet with the given transaction: puts it into the pending pool, sets the spent flags and runs
     * the onCoinsSent/onCoinsReceived event listener.
     * <p>
     * Triggers an auto save (if enabled.)
     * <p>
     * Unlike {@link Wallet#commitTx} this method does not throw an exception if the transaction
     * was already added to the wallet, instead it will return {@code false}
     *
     * @param tx transaction to commit
     * @return true if the tx was added to the wallet, or false if it was already in the pending pool
     * @throws VerificationException If transaction fails to verify
     */
    public boolean maybeCommitTx(Transaction tx) throws VerificationException {
        Transaction.verify(network, tx);
        lock.lock();
        try {
            if (pending.containsKey(tx.getTxId()))
                return false;
            log.info("commitTx of {}", tx.getTxId());
            Coin balance = getBalance();
            tx.setUpdateTime(TimeUtils.currentTime());
            // Put any outputs that are sending money back to us into the unspents map, and calculate their total value.
            Coin valueSentToMe = Coin.ZERO;
            for (TransactionOutput o : tx.getOutputs()) {
                if (!o.isMineOrWatched(this)) continue;
                valueSentToMe = valueSentToMe.add(o.getValue());
            }
            // Mark the outputs we're spending as spent so we won't try and use them in future creations. This will also
            // move any transactions that are now fully spent to the spent map so we can skip them when creating future
            // spends.
            updateForSpends(tx, false);

            Set<Transaction> doubleSpendPendingTxns = findDoubleSpendsAgainst(tx, pending);
            Set<Transaction> doubleSpendUnspentTxns = findDoubleSpendsAgainst(tx, unspent);
            Set<Transaction> doubleSpendSpentTxns = findDoubleSpendsAgainst(tx, spent);

            if (!doubleSpendUnspentTxns.isEmpty() ||
                !doubleSpendSpentTxns.isEmpty() ||
                !isNotSpendingTxnsInConfidenceType(tx, ConfidenceType.DEAD)) {
                // tx is a double spend against a tx already in the best chain or spends outputs of a DEAD tx.
                // Add tx to the dead pool and schedule confidence listener notifications.
                log.info("->dead: {}", tx.getTxId());
                getConfidence(tx).setConfidenceType(ConfidenceType.DEAD);
                confidenceChanged.put(tx, TransactionConfidence.Listener.ChangeReason.TYPE);
                addWalletTransaction(Pool.DEAD, tx);
            } else if (!doubleSpendPendingTxns.isEmpty() ||
                !isNotSpendingTxnsInConfidenceType(tx, ConfidenceType.IN_CONFLICT)) {
                // tx is a double spend against a pending tx or spends outputs of a tx already IN_CONFLICT.
                // Add tx to the pending pool. Update the confidence type of tx, the txns in conflict with tx and all
                // their dependencies to IN_CONFLICT and schedule confidence listener notifications.
                log.info("->pending (IN_CONFLICT): {}", tx.getTxId());
                addWalletTransaction(Pool.PENDING, tx);
                doubleSpendPendingTxns.add(tx);
                addTransactionsDependingOn(doubleSpendPendingTxns, getTransactions(true));
                for (Transaction doubleSpendTx : doubleSpendPendingTxns) {
                    getConfidence(doubleSpendTx).setConfidenceType(ConfidenceType.IN_CONFLICT);
                    confidenceChanged.put(doubleSpendTx, TransactionConfidence.Listener.ChangeReason.TYPE);
                }
            } else {
                // No conflict detected.
                // Add to the pending pool and schedule confidence listener notifications.
                log.info("->pending: {}", tx.getTxId());
                getConfidence(tx).setConfidenceType(ConfidenceType.PENDING);
                confidenceChanged.put(tx, TransactionConfidence.Listener.ChangeReason.TYPE);
                addWalletTransaction(Pool.PENDING, tx);
            }
            if (log.isInfoEnabled())
                log.info("Estimated balance is now: {}", getBalance(BalanceType.ESTIMATED).toFriendlyString());

            // Mark any keys used in the outputs as "used", this allows wallet UI's to auto-advance the current key
            // they are showing to the user in qr codes etc.
            markKeysAsUsed(tx);
            try {
                Coin valueSentFromMe = tx.getValueSentFromMe(this);
                Coin newBalance = balance.add(valueSentToMe).subtract(valueSentFromMe);
                if (valueSentToMe.signum() > 0) {
                    checkBalanceFuturesLocked();
                    queueOnCoinsReceived(tx, balance, newBalance);
                }
                if (valueSentFromMe.signum() > 0)
                    queueOnCoinsSent(tx, balance, newBalance);

                maybeQueueOnWalletChanged();
            } catch (ScriptException e) {
                // Cannot happen as we just created this transaction ourselves.
                throw new RuntimeException(e);
            }

            isConsistentOrThrow();
            informConfidenceListenersIfNotReorganizing();
            saveNow();
        } finally {
            lock.unlock();
        }
        return true;
    }

    /**
     * Updates the wallet with the given transaction: puts it into the pending pool, sets the spent flags and runs
     * the onCoinsSent/onCoinsReceived event listener. Used in two situations:
     * <ol>
     *     <li>When we have just successfully transmitted the tx we created to the network.</li>
     *     <li>When we receive a pending transaction that didn't appear in the chain yet, and we did not create it.</li>
     * </ol>
     * Triggers an auto save (if enabled.)
     * <p>
     * Unlike {@link Wallet#maybeCommitTx} {@code commitTx} throws an exception if the transaction
     * was already added to the wallet.
     *
     * @param tx transaction to commit
     * @throws VerificationException if transaction was already in the pending pool
     */
    public void commitTx(Transaction tx) throws VerificationException {
        checkArgument(maybeCommitTx(tx), () ->
                "commitTx called on the same transaction twice");
    }

    //endregion

    // ***************************************************************************************************************

    //region Event listeners

    /**
     * Adds an event listener object. Methods on this object are called when something interesting happens,
     * like receiving money. Runs the listener methods in the user thread.
     */
    public void addChangeEventListener(WalletChangeEventListener listener) {
        addChangeEventListener(Threading.USER_THREAD, listener);
    }

    /**
     * Adds an event listener object. Methods on this object are called when something interesting happens,
     * like receiving money. The listener is executed by the given executor.
     */
    public void addChangeEventListener(Executor executor, WalletChangeEventListener listener) {
        // This is thread safe, so we don't need to take the lock.
        changeListeners.add(new ListenerRegistration<>(listener, executor));
    }

    /**
     * Adds an event listener object called when coins are received.
     * Runs the listener methods in the user thread.
     */
    public void addCoinsReceivedEventListener(WalletCoinsReceivedEventListener listener) {
        addCoinsReceivedEventListener(Threading.USER_THREAD, listener);
    }

    /**
     * Adds an event listener object called when coins are received.
     * The listener is executed by the given executor.
     */
    public void addCoinsReceivedEventListener(Executor executor, WalletCoinsReceivedEventListener listener) {
        // This is thread safe, so we don't need to take the lock.
        coinsReceivedListeners.add(new ListenerRegistration<>(listener, executor));
    }

    /**
     * Adds an event listener object called when coins are sent.
     * Runs the listener methods in the user thread.
     */
    public void addCoinsSentEventListener(WalletCoinsSentEventListener listener) {
        addCoinsSentEventListener(Threading.USER_THREAD, listener);
    }

    /**
     * Adds an event listener object called when coins are sent.
     * The listener is executed by the given executor.
     */
    public void addCoinsSentEventListener(Executor executor, WalletCoinsSentEventListener listener) {
        // This is thread safe, so we don't need to take the lock.
        coinsSentListeners.add(new ListenerRegistration<>(listener, executor));
    }

    /**
     * Adds an event listener object. Methods on this object are called when keys are
     * added. The listener is executed in the user thread.
     */
    public void addKeyChainEventListener(KeyChainEventListener listener) {
        keyChainGroup.addEventListener(listener, Threading.USER_THREAD);
    }

    /**
     * Adds an event listener object. Methods on this object are called when keys are
     * added. The listener is executed by the given executor.
     */
    public void addKeyChainEventListener(Executor executor, KeyChainEventListener listener) {
        keyChainGroup.addEventListener(listener, executor);
    }

    /**
     * Adds an event listener object. Methods on this object are called when a current key and/or address
     * changes. The listener is executed in the user thread.
     */
    public void addCurrentKeyChangeEventListener(CurrentKeyChangeEventListener listener) {
        keyChainGroup.addCurrentKeyChangeEventListener(listener);
    }

    /**
     * Adds an event listener object. Methods on this object are called when a current key and/or address
     * changes. The listener is executed by the given executor.
     */
    public void addCurrentKeyChangeEventListener(Executor executor, CurrentKeyChangeEventListener listener) {
        keyChainGroup.addCurrentKeyChangeEventListener(listener, executor);
    }

    /**
     * Adds an event listener object. Methods on this object are called when something interesting happens,
     * like receiving money. Runs the listener methods in the user thread.
     */
    public void addReorganizeEventListener(WalletReorganizeEventListener listener) {
        addReorganizeEventListener(Threading.USER_THREAD, listener);
    }

    /**
     * Adds an event listener object. Methods on this object are called when something interesting happens,
     * like receiving money. The listener is executed by the given executor.
     */
    public void addReorganizeEventListener(Executor executor, WalletReorganizeEventListener listener) {
        // This is thread safe, so we don't need to take the lock.
        reorganizeListeners.add(new ListenerRegistration<>(listener, executor));
    }

    /**
     * Adds an event listener object. Methods on this object are called when scripts
     * watched by this wallet change. Runs the listener methods in the user thread.
     */
    public void addScriptsChangeEventListener(ScriptsChangeEventListener listener) {
        addScriptsChangeEventListener(Threading.USER_THREAD, listener);
    }

    /**
     * Adds an event listener object. Methods on this object are called when scripts
     * watched by this wallet change. The listener is executed by the given executor.
     */
    public void addScriptsChangeEventListener(Executor executor, ScriptsChangeEventListener listener) {
        // This is thread safe, so we don't need to take the lock.
        scriptsChangeListeners.add(new ListenerRegistration<>(listener, executor));
    }

    /**
     * Adds an event listener object. Methods on this object are called when confidence
     * of a transaction changes. Runs the listener methods in the user thread.
     */
    public void addTransactionConfidenceEventListener(TransactionConfidenceEventListener listener) {
        addTransactionConfidenceEventListener(Threading.USER_THREAD, listener);
    }

    /**
     * Adds an event listener object. Methods on this object are called when confidence
     * of a transaction changes. The listener is executed by the given executor.
     */
    public void addTransactionConfidenceEventListener(Executor executor, TransactionConfidenceEventListener listener) {
        // This is thread safe, so we don't need to take the lock.
        transactionConfidenceListeners.add(new ListenerRegistration<>(listener, executor));
    }

    /**
     * Removes the given event listener object. Returns true if the listener was removed, false if that listener
     * was never added.
     */
    public boolean removeChangeEventListener(WalletChangeEventListener listener) {
        return ListenerRegistration.removeFromList(listener, changeListeners);
    }

    /**
     * Removes the given event listener object. Returns true if the listener was removed, false if that listener
     * was never added.
     */
    public boolean removeCoinsReceivedEventListener(WalletCoinsReceivedEventListener listener) {
        return ListenerRegistration.removeFromList(listener, coinsReceivedListeners);
    }

    /**
     * Removes the given event listener object. Returns true if the listener was removed, false if that listener
     * was never added.
     */
    public boolean removeCoinsSentEventListener(WalletCoinsSentEventListener listener) {
        return ListenerRegistration.removeFromList(listener, coinsSentListeners);
    }

    /**
     * Removes the given event listener object. Returns true if the listener was removed, false if that listener
     * was never added.
     */
    public boolean removeKeyChainEventListener(KeyChainEventListener listener) {
        return keyChainGroup.removeEventListener(listener);
    }

    /**
     * Removes the given event listener object. Returns true if the listener was removed, false if that
     * listener was never added.
     */
    public boolean removeCurrentKeyChangeEventListener(CurrentKeyChangeEventListener listener) {
        return keyChainGroup.removeCurrentKeyChangeEventListener(listener);
    }

    /**
     * Removes the given event listener object. Returns true if the listener was removed, false if that listener
     * was never added.
     */
    public boolean removeReorganizeEventListener(WalletReorganizeEventListener listener) {
        return ListenerRegistration.removeFromList(listener, reorganizeListeners);
    }

    /**
     * Removes the given event listener object. Returns true if the listener was removed, false if that listener
     * was never added.
     */
    public boolean removeScriptsChangeEventListener(ScriptsChangeEventListener listener) {
        return ListenerRegistration.removeFromList(listener, scriptsChangeListeners);
    }

    /**
     * Removes the given event listener object. Returns true if the listener was removed, false if that listener
     * was never added.
     */
    public boolean removeTransactionConfidenceEventListener(TransactionConfidenceEventListener listener) {
        return ListenerRegistration.removeFromList(listener, transactionConfidenceListeners);
    }

    private void queueOnTransactionConfidenceChanged(final Transaction tx) {
        checkState(lock.isHeldByCurrentThread());
        for (final ListenerRegistration<TransactionConfidenceEventListener> registration : transactionConfidenceListeners) {
            if (registration.executor == Threading.SAME_THREAD) {
                registration.listener.onTransactionConfidenceChanged(this, tx);
            } else {
                registration.executor.execute(() -> registration.listener.onTransactionConfidenceChanged(Wallet.this, tx));
            }
        }
    }

    protected void maybeQueueOnWalletChanged() {
        // Don't invoke the callback in some circumstances, eg, whilst we are re-organizing or fiddling with
        // transactions due to a new block arriving. It will be called later instead.
        checkState(lock.isHeldByCurrentThread());
        checkState(onWalletChangedSuppressions >= 0);
        if (onWalletChangedSuppressions > 0) return;
        for (final ListenerRegistration<WalletChangeEventListener> registration : changeListeners) {
            registration.executor.execute(() -> registration.listener.onWalletChanged(Wallet.this));
        }
    }

    protected void queueOnCoinsReceived(final Transaction tx, final Coin balance, final Coin newBalance) {
        checkState(lock.isHeldByCurrentThread());
        for (final ListenerRegistration<WalletCoinsReceivedEventListener> registration : coinsReceivedListeners) {
            registration.executor.execute(() -> registration.listener.onCoinsReceived(Wallet.this, tx, balance, newBalance));
        }
    }

    protected void queueOnCoinsSent(final Transaction tx, final Coin prevBalance, final Coin newBalance) {
        checkState(lock.isHeldByCurrentThread());
        for (final ListenerRegistration<WalletCoinsSentEventListener> registration : coinsSentListeners) {
            registration.executor.execute(() -> registration.listener.onCoinsSent(Wallet.this, tx, prevBalance, newBalance));
        }
    }

    protected void queueOnReorganize() {
        checkState(lock.isHeldByCurrentThread());
        checkState(insideReorg);
        for (final ListenerRegistration<WalletReorganizeEventListener> registration : reorganizeListeners) {
            registration.executor.execute(() -> registration.listener.onReorganize(Wallet.this));
        }
    }

    protected void queueOnScriptsChanged(final List<Script> scripts, final boolean isAddingScripts) {
        for (final ListenerRegistration<ScriptsChangeEventListener> registration : scriptsChangeListeners) {
            registration.executor.execute(() -> registration.listener.onScriptsChanged(Wallet.this, scripts, isAddingScripts));
        }
    }

    //endregion

    // ***************************************************************************************************************

    //region Vending transactions and other internal state

    /**
     * Returns a set of all transactions in the wallet.
     * @param includeDead     If true, transactions that were overridden by a double spend are included.
     */
    public Set<Transaction> getTransactions(boolean includeDead) {
        lock.lock();
        try {
            Set<Transaction> all = new HashSet<>();
            all.addAll(unspent.values());
            all.addAll(spent.values());
            all.addAll(pending.values());
            if (includeDead)
                all.addAll(dead.values());
            return all;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a set of all WalletTransactions in the wallet.
     */
    public Iterable<WalletTransaction> getWalletTransactions() {
        lock.lock();
        try {
            Set<WalletTransaction> all = new HashSet<>();
            addWalletTransactionsToSet(all, Pool.UNSPENT, unspent.values());
            addWalletTransactionsToSet(all, Pool.SPENT, spent.values());
            addWalletTransactionsToSet(all, Pool.DEAD, dead.values());
            addWalletTransactionsToSet(all, Pool.PENDING, pending.values());
            return all;
        } finally {
            lock.unlock();
        }
    }

    private static void addWalletTransactionsToSet(Set<WalletTransaction> txns,
                                                   Pool poolType, Collection<Transaction> pool) {
        for (Transaction tx : pool) {
            txns.add(new WalletTransaction(poolType, tx));
        }
    }

    /**
     * Adds a transaction that has been associated with a particular wallet pool. This is intended for usage by
     * deserialization code, such as the {@link WalletProtobufSerializer} class. It isn't normally useful for
     * applications. It does not trigger auto saving.
     */
    public void addWalletTransaction(WalletTransaction wtx) {
        lock.lock();
        try {
            addWalletTransaction(wtx.getPool(), wtx.getTransaction());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adds the given transaction to the given pools and registers a confidence change listener on it.
     */
    private void addWalletTransaction(Pool pool, Transaction tx) {
        checkState(lock.isHeldByCurrentThread());
        transactions.put(tx.getTxId(), tx);
        switch (pool) {
        case UNSPENT:
            checkState(unspent.put(tx.getTxId(), tx) == null);
            break;
        case SPENT:
            checkState(spent.put(tx.getTxId(), tx) == null);
            break;
        case PENDING:
            checkState(pending.put(tx.getTxId(), tx) == null);
            break;
        case DEAD:
            checkState(dead.put(tx.getTxId(), tx) == null);
            break;
        default:
            throw new RuntimeException("Unknown wallet transaction type " + pool);
        }
        if (pool == Pool.UNSPENT || pool == Pool.PENDING) {
            for (TransactionOutput output : tx.getOutputs()) {
                if (output.isAvailableForSpending() && output.isMineOrWatched(this))
                    myUnspents.add(output);
            }
        }
        // This is safe even if the listener has been added before, as TransactionConfidence ignores duplicate
        // registration requests. That makes the code in the wallet simpler.
        getConfidence(tx).addEventListener(Threading.SAME_THREAD, txConfidenceListener);
    }

    /**
     * Returns all non-dead, active transactions ordered by recency.
     */
    public List<Transaction> getTransactionsByTime() {
        return getRecentTransactions(0, false);
    }

    /**
     * <p>Returns an list of N transactions, ordered by increasing age. Transactions on side chains are not included.
     * Dead transactions (overridden by double spends) are optionally included.</p>
     * <p>Note: the current implementation is O(num transactions in wallet). Regardless of how many transactions are
     * requested, the cost is always the same. In future, requesting smaller numbers of transactions may be faster
     * depending on how the wallet is implemented (e.g. if backed by a database).</p>
     */
    public List<Transaction> getRecentTransactions(int numTransactions, boolean includeDead) {
        lock.lock();
        try {
            checkArgument(numTransactions >= 0);
            // Firstly, put all transactions into an array.
            int size = unspent.size() + spent.size() + pending.size();
            if (numTransactions > size || numTransactions == 0) {
                numTransactions = size;
            }
            List<Transaction> all = new ArrayList<>(getTransactions(includeDead));
            // Order by update time.
            Collections.sort(all, Transaction.SORT_TX_BY_UPDATE_TIME);
            if (numTransactions == all.size()) {
                return all;
            } else {
                all.subList(numTransactions, all.size()).clear();
                return all;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a transaction object given its hash, if it exists in this wallet, or null otherwise.
     */
    @Nullable
    public Transaction getTransaction(Sha256Hash hash) {
        lock.lock();
        try {
            return transactions.get(hash);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Map<Sha256Hash, Transaction> getTransactionPool(Pool pool) {
        lock.lock();
        try {
            switch (pool) {
                case UNSPENT:
                    return unspent;
                case SPENT:
                    return spent;
                case PENDING:
                    return pending;
                case DEAD:
                    return dead;
                default:
                    throw new RuntimeException("Unknown wallet transaction type " + pool);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Prepares the wallet for a blockchain replay. Removes all transactions (as they would get in the way of the
     * replay) and makes the wallet think it has never seen a block. {@link WalletChangeEventListener#onWalletChanged} will
     * be fired.
     */
    public void reset() {
        lock.lock();
        try {
            clearTransactions();
            lastBlockSeenHash = null;
            lastBlockSeenHeight = -1; // Magic value for 'never'.
            lastBlockSeenTime = null;
            saveLater();
            maybeQueueOnWalletChanged();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Deletes transactions which appeared above the given block height from the wallet, but does not touch the keys.
     * This is useful if you have some keys and wish to replay the block chain into the wallet in order to pick them up.
     * Triggers auto saving.
     */
    public void clearTransactions(int fromHeight) {
        lock.lock();
        try {
            if (fromHeight == 0) {
                clearTransactions();
                saveLater();
            } else {
                throw new UnsupportedOperationException();
            }
        } finally {
            lock.unlock();
        }
    }

    private void clearTransactions() {
        unspent.clear();
        spent.clear();
        pending.clear();
        dead.clear();
        transactions.clear();
        myUnspents.clear();
    }

    /**
     * Returns all the outputs that match addresses or scripts added via {@link #addWatchedAddress(Address)} or
     * {@link #addWatchedScripts(java.util.List)}.
     * @param excludeImmatureCoinbases Whether to ignore outputs that are unspendable due to being immature.
     */
    public List<TransactionOutput> getWatchedOutputs(boolean excludeImmatureCoinbases) {
        lock.lock();
        keyChainGroupLock.lock();
        try {
            return Stream.concat(unspent.values().stream(), pending.values().stream())
                    .filter(tx -> !excludeImmatureCoinbases || isTransactionMature(tx))
                    .flatMap(tx -> tx.getOutputs().stream())
                            .filter(TransactionOutput::isAvailableForSpending)
                            .filter(this::isWatchedScriptPubKey)
                            .collect(StreamUtils.toUnmodifiableList());
        } finally {
            keyChainGroupLock.unlock();
            lock.unlock();
        }
    }

    private boolean isWatchedScriptPubKey(TransactionOutput output) {
        try {
            Script scriptPubKey = output.getScriptPubKey();
            return watchedScripts.contains(scriptPubKey);
        } catch (ScriptException e) {
            return false;
        }
    }

    /**
     * Clean up the wallet. Currently, it only removes risky pending transaction from the wallet and only if their
     * outputs have not been spent.
     */
    public void cleanup() {
        lock.lock();
        try {
            boolean dirty = false;
            for (Iterator<Transaction> i = pending.values().iterator(); i.hasNext();) {
                Transaction tx = i.next();
                if (isTransactionRisky(tx, null) && !acceptRiskyTransactions) {
                    log.debug("Found risky transaction {} in wallet during cleanup.", tx.getTxId());
                    if (!tx.isAnyOutputSpent()) {
                        // Sync myUnspents with the change.
                        for (TransactionInput input : tx.getInputs()) {
                            TransactionOutput output = input.getConnectedOutput();
                            if (output == null) continue;
                            if (output.isMineOrWatched(this))
                                checkState(myUnspents.add(output));
                            input.disconnect();
                        }
                        for (TransactionOutput output : tx.getOutputs())
                            myUnspents.remove(output);

                        i.remove();
                        transactions.remove(tx.getTxId());
                        dirty = true;
                        log.info("Removed transaction {} from pending pool during cleanup.", tx.getTxId());
                    } else {
                        log.info(
                                "Cannot remove transaction {} from pending pool during cleanup, as it's already spent partially.",
                                tx.getTxId());
                    }
                }
            }
            if (dirty) {
                isConsistentOrThrow();
                saveLater();
                if (log.isInfoEnabled())
                    log.info("Estimated balance is now: {}", getBalance(BalanceType.ESTIMATED).toFriendlyString());
            }
        } finally {
            lock.unlock();
        }
    }

    EnumSet<Pool> getContainingPools(Transaction tx) {
        lock.lock();
        try {
            EnumSet<Pool> result = EnumSet.noneOf(Pool.class);
            Sha256Hash txHash = tx.getTxId();
            if (unspent.containsKey(txHash)) {
                result.add(Pool.UNSPENT);
            }
            if (spent.containsKey(txHash)) {
                result.add(Pool.SPENT);
            }
            if (pending.containsKey(txHash)) {
                result.add(Pool.PENDING);
            }
            if (dead.containsKey(txHash)) {
                result.add(Pool.DEAD);
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    @VisibleForTesting
    public int getPoolSize(WalletTransaction.Pool pool) {
        lock.lock();
        try {
            switch (pool) {
                case UNSPENT:
                    return unspent.size();
                case SPENT:
                    return spent.size();
                case PENDING:
                    return pending.size();
                case DEAD:
                    return dead.size();
            }
            throw new RuntimeException("Unreachable");
        } finally {
            lock.unlock();
        }
    }

    @VisibleForTesting
    public boolean poolContainsTxHash(final WalletTransaction.Pool pool, final Sha256Hash txHash) {
        lock.lock();
        try {
            switch (pool) {
                case UNSPENT:
                    return unspent.containsKey(txHash);
                case SPENT:
                    return spent.containsKey(txHash);
                case PENDING:
                    return pending.containsKey(txHash);
                case DEAD:
                    return dead.containsKey(txHash);
            }
            throw new RuntimeException("Unreachable");
        } finally {
            lock.unlock();
        }
    }

    /** Returns a copy of the internal unspent outputs list */
    public List<TransactionOutput> getUnspents() {
        lock.lock();
        try {
            return new ArrayList<>(myUnspents);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return toString(false, false, null, true, true, null);
    }

    /**
     * Formats the wallet as a human-readable piece of text. Intended for debugging, the format is not meant to be
     * stable or human-readable.
     * @param includeLookahead Whether lookahead keys should be included.
     * @param includePrivateKeys Whether raw private key data should be included.
     * @param aesKey for decrypting private key data for if the wallet is encrypted.
     * @param includeTransactions Whether to print transaction data.
     * @param includeExtensions Whether to print extension data.
     * @param chain If set, will be used to estimate lock times for block time-locked transactions.
     * @return Human-readable wallet debugging information
     */
    public String toString(boolean includeLookahead, boolean includePrivateKeys, @Nullable AesKey aesKey,
            boolean includeTransactions, boolean includeExtensions, @Nullable AbstractBlockChain chain) {
        lock.lock();
        keyChainGroupLock.lock();
        try {
            StringBuilder builder = new StringBuilder("Wallet\n");
            if (includePrivateKeys)
                builder.append("  WARNING: includes private keys!\n");
            builder.append("Balances:\n");
            for (BalanceType balanceType : BalanceType.values())
                builder.append("  ").append(getBalance(balanceType).toFriendlyString()).append(' ').append(balanceType)
                        .append('\n');
            builder.append("Transactions:\n");
            builder.append("  ").append(pending.size()).append(" pending\n");
            builder.append("  ").append(unspent.size()).append(" unspent\n");
            builder.append("  ").append(spent.size()).append(" spent\n");
            builder.append("  ").append(dead.size()).append(" dead\n");
            builder.append("Last seen best block: ").append(getLastBlockSeenHeight()).append(" (")
                    .append(lastBlockSeenTime()
                            .map(instant -> TimeUtils.dateTimeFormat(instant))
                            .orElse("time unknown"))
                    .append("): ").append(getLastBlockSeenHash()).append('\n');
            final KeyCrypter crypter = keyChainGroup.getKeyCrypter();
            if (crypter != null)
                builder.append("Encryption: ").append(crypter).append('\n');
            if (isWatching())
                builder.append("Wallet is watching.\n");

            // Do the keys.
            builder.append("\nKeys:\n");
            builder.append("Earliest creation time: ").append(TimeUtils.dateTimeFormat(earliestKeyCreationTime()))
                    .append('\n');
            final Optional<Instant> keyRotationTime = keyRotationTime();
            if (keyRotationTime.isPresent())
                builder.append("Key rotation time:      ").append(TimeUtils.dateTimeFormat(keyRotationTime.get())).append('\n');
            builder.append(keyChainGroup.toString(includeLookahead, includePrivateKeys, aesKey));

            if (!watchedScripts.isEmpty()) {
                builder.append("\nWatched scripts:\n");
                for (Script script : watchedScripts) {
                    builder.append("  ").append(script).append("\n");
                }
            }

            if (includeTransactions) {
                // Print the transactions themselves
                if (pending.size() > 0) {
                    builder.append("\n>>> PENDING:\n");
                    toStringHelper(builder, pending, chain, Transaction.SORT_TX_BY_UPDATE_TIME);
                }
                if (unspent.size() > 0) {
                    builder.append("\n>>> UNSPENT:\n");
                    toStringHelper(builder, unspent, chain, Transaction.SORT_TX_BY_HEIGHT);
                }
                if (spent.size() > 0) {
                    builder.append("\n>>> SPENT:\n");
                    toStringHelper(builder, spent, chain, Transaction.SORT_TX_BY_HEIGHT);
                }
                if (dead.size() > 0) {
                    builder.append("\n>>> DEAD:\n");
                    toStringHelper(builder, dead, chain, Transaction.SORT_TX_BY_UPDATE_TIME);
                }
            }
            if (includeExtensions && extensions.size() > 0) {
                builder.append("\n>>> EXTENSIONS:\n");
                for (WalletExtension extension : extensions.values()) {
                    builder.append(extension).append("\n\n");
                }
            }
            return builder.toString();
        } finally {
            keyChainGroupLock.unlock();
            lock.unlock();
        }
    }

    private void toStringHelper(StringBuilder builder, Map<Sha256Hash, Transaction> transactionMap,
                                @Nullable AbstractBlockChain chain, @Nullable Comparator<Transaction> sortOrder) {
        checkState(lock.isHeldByCurrentThread());

        final Collection<Transaction> txns;
        if (sortOrder != null) {
            txns = new TreeSet<>(sortOrder);
            txns.addAll(transactionMap.values());
        } else {
            txns = transactionMap.values();
        }

        for (Transaction tx : txns) {
            try {
                builder.append(tx.getValue(this).toFriendlyString());
                builder.append(" total value (sends ");
                builder.append(tx.getValueSentFromMe(this).toFriendlyString());
                builder.append(" and receives ");
                builder.append(tx.getValueSentToMe(this).toFriendlyString());
                builder.append(")\n");
            } catch (ScriptException e) {
                // Ignore and don't print this line.
            }
            if (tx.hasConfidence())
                builder.append("  confidence: ").append(getConfidence(tx)).append('\n');
            builder.append(tx.toString(chain, network(), "  "));
        }
    }

    /**
     * Returns an immutable view of the transactions currently waiting for network confirmations.
     */
    public Collection<Transaction> getPendingTransactions() {
        lock.lock();
        try {
            return Collections.unmodifiableCollection(pending.values());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the earliest creation time of keys or watched scripts in this wallet, ie the min
     * of {@link ECKey#getCreationTime()}. This can return {@link Instant#EPOCH} if at least one key does
     * not have that data (e.g. is an imported key with unknown timestamp). <p>
     *
     * This method is most often used in conjunction with {@link PeerGroup#setFastCatchupTime(Instant)} in order to
     * optimize chain download for new users of wallet apps. Backwards compatibility notice: if you get {@link Instant#EPOCH} from this
     * method, you can instead use the time of the first release of your software, as it's guaranteed no users will
     * have wallets pre-dating this time. <p>
     *
     * If there are no keys in the wallet, {@link Instant#MAX} is returned.
     *
     * @return earliest creation times of keys in this wallet,
     *         {@link Instant#EPOCH} if at least one time is unknown,
     *         {@link Instant#MAX} if no keys in this wallet
     */
    @Override
    public Instant earliestKeyCreationTime() {
        keyChainGroupLock.lock();
        try {
            Instant earliestTime = keyChainGroup.earliestKeyCreationTime();
            for (Script script : watchedScripts)
                earliestTime = TimeUtils.earlier(script.creationTime().orElse(Instant.EPOCH), earliestTime);
            return earliestTime;
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** Returns the hash of the last seen best-chain block, or null if the wallet is too old to store this data. */
    @Nullable
    public Sha256Hash getLastBlockSeenHash() {
        lock.lock();
        try {
            return lastBlockSeenHash;
        } finally {
            lock.unlock();
        }
    }

    public void setLastBlockSeenHash(@Nullable Sha256Hash lastBlockSeenHash) {
        lock.lock();
        try {
            this.lastBlockSeenHash = lastBlockSeenHash;
        } finally {
            lock.unlock();
        }
    }

    public void setLastBlockSeenHeight(int lastBlockSeenHeight) {
        lock.lock();
        try {
            this.lastBlockSeenHeight = lastBlockSeenHeight;
        } finally {
            lock.unlock();
        }
    }

    public void setLastBlockSeenTime(Instant time) {
        lock.lock();
        try {
            lastBlockSeenTime = Objects.requireNonNull(time);
        } finally {
            lock.unlock();
        }
    }

    public void clearLastBlockSeenTime() {
        lock.lock();
        try {
            lastBlockSeenTime = null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns time extracted from the last best seen block header, or empty. This timestamp
     * is <b>not</b> the local time at which the block was first observed by this application but rather what the block
     * (i.e. miner) self declares. It is allowed to have some significant drift from the real time at which the block
     * was found, although most miners do use accurate times. If this wallet is old and does not have a recorded
     * time then this method returns zero.
     */
    public Optional<Instant> lastBlockSeenTime() {
        lock.lock();
        try {
            return Optional.ofNullable(lastBlockSeenTime);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the height of the last seen best-chain block. Can be 0 if a wallet is brand new or -1 if the wallet
     * is old and doesn't have that data.
     */
    public int getLastBlockSeenHeight() {
        lock.lock();
        try {
            return lastBlockSeenHeight;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the version of the Wallet.
     * This is an int you can use to indicate which versions of wallets your code understands,
     * and which come from the future (and hence cannot be safely loaded).
     */
    public int getVersion() {
        return version;
    }

    /**
     * Set the version number of the wallet. See {@link Wallet#getVersion()}.
     */
    public void setVersion(int version) {
        this.version = version;
    }

    /**
     * Set the description of the wallet.
     * This is a Unicode encoding string typically entered by the user as descriptive text for the wallet.
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Get the description of the wallet. See {@link Wallet#setDescription(String)}
     */
    public String getDescription() {
        return description;
    }

    //endregion

    // ***************************************************************************************************************

    //region Balance and balance futures

    /**
     * <p>It's possible to calculate a wallets balance from multiple points of view. This enum selects which
     * {@link #getBalance(BalanceType)} should use.</p>
     *
     * <p>Consider a real-world example: you buy a snack costing $5 but you only have a $10 bill. At the start you have
     * $10 viewed from every possible angle. After you order the snack you hand over your $10 bill. From the
     * perspective of your wallet you have zero dollars (AVAILABLE). But you know in a few seconds the shopkeeper
     * will give you back $5 change so most people in practice would say they have $5 (ESTIMATED).</p>
     *
     * <p>The fact that the wallet can track transactions which are not spendable by itself ("watching wallets") adds
     * another type of balance to the mix. Although the wallet won't do this by default, advanced use cases that
     * override the relevancy checks can end up with a mix of spendable and unspendable transactions.</p>
     */
    public enum BalanceType {
        /**
         * Balance calculated assuming all pending transactions are in fact included into the best chain by miners.
         * This includes the value of immature coinbase transactions.
         */
        ESTIMATED,

        /**
         * Balance that could be safely used to create new spends, if we had all the needed private keys. This is
         * whatever the default coin selector would make available, which by default means transaction outputs with at
         * least 1 confirmation and pending transactions created by our own wallet which have been propagated across
         * the network. Whether we <i>actually</i> have the private keys or not is irrelevant for this balance type.
         */
        AVAILABLE,

        /** Same as ESTIMATED but only for outputs we have the private keys for and can sign ourselves. */
        ESTIMATED_SPENDABLE,
        /** Same as AVAILABLE but only for outputs we have the private keys for and can sign ourselves. */
        AVAILABLE_SPENDABLE
    }

    /**
     * Returns the AVAILABLE balance of this wallet. See {@link BalanceType#AVAILABLE} for details on what this
     * means.
     */
    public Coin getBalance() {
        return getBalance(BalanceType.AVAILABLE);
    }

    /**
     * Returns the balance of this wallet as calculated by the provided balanceType.
     */
    public Coin getBalance(BalanceType balanceType) {
        lock.lock();
        try {
            if (balanceType == BalanceType.AVAILABLE || balanceType == BalanceType.AVAILABLE_SPENDABLE) {
                List<TransactionOutput> candidates = calculateAllSpendCandidates(true, balanceType == BalanceType.AVAILABLE_SPENDABLE);
                CoinSelection selection = coinSelector.select(BitcoinNetwork.MAX_MONEY, candidates);
                return selection.totalValue();
            } else if (balanceType == BalanceType.ESTIMATED || balanceType == BalanceType.ESTIMATED_SPENDABLE) {
                List<TransactionOutput> all = calculateAllSpendCandidates(false, balanceType == BalanceType.ESTIMATED_SPENDABLE);
                Coin value = Coin.ZERO;
                for (TransactionOutput out : all) value = value.add(out.getValue());
                return value;
            } else {
                throw new AssertionError("Unknown balance type");  // Unreachable.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the balance that would be considered spendable by the given coin selector, including watched outputs
     * (i.e. balance includes outputs we don't have the private keys for). Just asks it to select as many coins as
     * possible and returns the total.
     */
    public Coin getBalance(CoinSelector selector) {
        lock.lock();
        try {
            Objects.requireNonNull(selector);
            List<TransactionOutput> candidates = calculateAllSpendCandidates(true, false);
            CoinSelection selection = selector.select((Coin) network.maxMoney(), candidates);
            return selection.totalValue();
        } finally {
            lock.unlock();
        }
    }

    private static class BalanceFutureRequest {
        public final CompletableFuture<Coin> future;
        public final Coin value;
        public final BalanceType type;

        private BalanceFutureRequest(CompletableFuture<Coin> future, Coin value, BalanceType type) {
            this.future = future;
            this.value = value;
            this.type = type;
        }
    }
    @GuardedBy("lock") private final List<BalanceFutureRequest> balanceFutureRequests = new LinkedList<>();

    /**
     * <p>Returns a future that will complete when the balance of the given type has becom equal or larger to the given
     * value. If the wallet already has a large enough balance the future is returned in a pre-completed state. Note
     * that this method is not blocking, if you want to actually wait immediately, you have to call .get() on
     * the result.</p>
     *
     * <p>Also note that by the time the future completes, the wallet may have changed yet again if something else
     * is going on in parallel, so you should treat the returned balance as advisory and be prepared for sending
     * money to fail! Finally please be aware that any listeners on the future will run either on the calling thread
     * if it completes immediately, or eventually on a background thread if the balance is not yet at the right
     * level. If you do something that means you know the balance should be sufficient to trigger the future,
     * you can use {@link Threading#waitForUserCode()} to block until the future had a
     * chance to be updated.</p>
     */
    public CompletableFuture<Coin> getBalanceFuture(final Coin value, final BalanceType type) {
        lock.lock();
        try {
            final CompletableFuture<Coin> future = new CompletableFuture<>();
            final Coin current = getBalance(type);
            if (current.compareTo(value) >= 0) {
                // Already have enough.
                future.complete(current);
            } else {
                // Will be checked later in checkBalanceFutures. We don't just add an event listener for ourselves
                // here so that running getBalanceFuture().get() in the user code thread works - generally we must
                // avoid giving the user back futures that require the user code thread to be free.
                balanceFutureRequests.add(new BalanceFutureRequest(future, value, type));
            }
            return future;
        } finally {
            lock.unlock();
        }
    }

    // Runs any balance futures in the user code thread.
    @SuppressWarnings("FieldAccessNotGuarded")
    private void checkBalanceFuturesLocked() {
        checkState(lock.isHeldByCurrentThread());
        balanceFutureRequests.forEach(req -> {
            Coin current = getBalance(req.type);   // This could be slow for lots of futures.
            if (current.compareTo(req.value) >= 0) {
                // Found one that's finished.
                // Don't run any user-provided future listeners with our lock held.
                Threading.USER_THREAD.execute(() -> req.future.complete(current));
            }
        });
        balanceFutureRequests.removeIf(req -> req.future.isDone());
    }

    /**
     * Returns the amount of bitcoin ever received via output. <b>This is not the balance!</b> If an output spends from a
     * transaction whose inputs are also to our wallet, the input amounts are deducted from the outputs contribution, with a minimum of zero
     * contribution. The idea behind this is we avoid double counting money sent to us.
     * @return the total amount of satoshis received, regardless of whether it was spent or not.
     */
    public Coin getTotalReceived() {
        Coin total = Coin.ZERO;

        // Include outputs to us if they were not just change outputs, ie the inputs to us summed to less
        // than the outputs to us.
        for (Transaction tx: transactions.values()) {
            Coin txTotal = Coin.ZERO;
            for (TransactionOutput output : tx.getOutputs()) {
                if (output.isMine(this)) {
                    txTotal = txTotal.add(output.getValue());
                }
            }
            for (TransactionInput in : tx.getInputs()) {
                TransactionOutput prevOut = in.getConnectedOutput();
                if (prevOut != null && prevOut.isMine(this)) {
                    txTotal = txTotal.subtract(prevOut.getValue());
                }
            }
            if (txTotal.isPositive()) {
                total = total.add(txTotal);
            }
        }
        return total;
    }

    /**
     * Returns the amount of bitcoin ever sent via output. If an output is sent to our own wallet, because of change or
     * rotating keys or whatever, we do not count it. If the wallet was
     * involved in a shared transaction, i.e. there is some input to the transaction that we don't have the key for, then
     * we multiply the sum of the output values by the proportion of satoshi coming in to our inputs. Essentially we treat
     * inputs as pooling into the transaction, becoming fungible and being equally distributed to all outputs.
     * @return the total amount of satoshis sent by us
     */
    public Coin getTotalSent() {
        Coin total = Coin.ZERO;

        for (Transaction tx: transactions.values()) {
            // Count spent outputs to only if they were not to us. This means we don't count change outputs.
            Coin txOutputTotal = Coin.ZERO;
            for (TransactionOutput out : tx.getOutputs()) {
                if (out.isMine(this) == false) {
                    txOutputTotal = txOutputTotal.add(out.getValue());
                }
            }

            // Count the input values to us
            Coin txOwnedInputsTotal = Coin.ZERO;
            for (TransactionInput in : tx.getInputs()) {
                TransactionOutput prevOut = in.getConnectedOutput();
                if (prevOut != null && prevOut.isMine(this)) {
                    txOwnedInputsTotal = txOwnedInputsTotal.add(prevOut.getValue());
                }
            }

            // If there is an input that isn't from us, i.e. this is a shared transaction
            Coin txInputsTotal = tx.getInputSum();
            if (!txOwnedInputsTotal.equals(txInputsTotal)) {

                // multiply our output total by the appropriate proportion to account for the inputs that we don't own
                BigInteger txOutputTotalNum = new BigInteger(txOutputTotal.toString());
                txOutputTotalNum = txOutputTotalNum.multiply(new BigInteger(txOwnedInputsTotal.toString()));
                txOutputTotalNum = txOutputTotalNum.divide(new BigInteger(txInputsTotal.toString()));
                txOutputTotal = Coin.valueOf(txOutputTotalNum.longValue());
            }
            total = total.add(txOutputTotal);

        }
        return total;
    }

    //endregion

    // ***************************************************************************************************************

    //region Creating and sending transactions

    /**
     * A SendResult is returned to you as part of sending coins to a recipient.
     * @deprecated  Use {@link TransactionBroadcast}
     */
    @Deprecated
    public interface SendResult {
        Transaction transaction();

        @Deprecated
        TransactionBroadcast getBroadcast();

        CompletableFuture<TransactionBroadcast> awaitRelayed();
    }

    /**
     * Enumerates possible resolutions for missing signatures.
     */
    public enum MissingSigsMode {
        /** Input script will have OP_0 instead of missing signatures */
        USE_OP_ZERO,
        /**
         * Missing signatures will be replaced by dummy sigs. This is useful when you'd like to know the fee for
         * a transaction without knowing the user's password, as fee depends on size.
         */
        USE_DUMMY_SIG,
        /**
         * If signature is missing, {@link TransactionSigner.MissingSignatureException}
         * will be thrown for P2SH and {@link ECKey.MissingPrivateKeyException} for other tx types.
         */
        THROW
    }

    /**
     * <p>Statelessly creates a transaction that sends the given value to address. The change is sent to
     * {@link Wallet#currentChangeAddress()}, so you must have added at least one key.</p>
     *
     * <p>If you just want to send money quickly, you probably want
     * {@link Wallet#sendCoins(TransactionBroadcaster, Address, Coin)} instead. That will create the sending
     * transaction, commit to the wallet and broadcast it to the network all in one go. This method is lower level
     * and lets you see the proposed transaction before anything is done with it.</p>
     *
     * <p>This is a helper method that is equivalent to using {@link SendRequest#to(Address, Coin)}
     * followed by {@link Wallet#completeTx(SendRequest)} and returning the requests transaction object.
     * Note that this means a fee may be automatically added if required, if you want more control over the process,
     * just do those two steps yourself.</p>
     *
     * <p>IMPORTANT: This method does NOT update the wallet. If you call createSend again you may get two transactions
     * that spend the same coins. You have to call {@link Wallet#commitTx(Transaction)} on the created transaction to
     * prevent this, but that should only occur once the transaction has been accepted by the network. This implies
     * you cannot have more than one outstanding sending tx at once.</p>
     *
     * <p>You MUST ensure that the value is not smaller than {@link TransactionOutput#getMinNonDustValue()} or the transaction
     * will almost certainly be rejected by the network as dust.</p>
     *
     * @param address The Bitcoin address to send the money to.
     * @param value How much currency to send.
     * @return either the created Transaction or null if there are insufficient coins.
     * @throws InsufficientMoneyException if the request could not be completed due to not enough balance.
     * @throws DustySendRequested if the resultant transaction would violate the dust rules.
     * @throws CouldNotAdjustDownwards if emptying the wallet was requested and the output can't be shrunk for fees without violating a protocol rule.
     * @throws ExceededMaxTransactionSize if the resultant transaction is too big for Bitcoin to process.
     * @throws MultipleOpReturnRequested if there is more than one OP_RETURN output for the resultant transaction.
     * @throws BadWalletEncryptionKeyException if the supplied {@link SendRequest#aesKey} is wrong.
     */
    public Transaction createSend(Address address, Coin value)
            throws InsufficientMoneyException, TransactionCompletionException {
        return createSend(address, value, false);
    }

    /**
     * <p>Statelessly creates a transaction that sends the given value to address. The change is sent to
     * {@link Wallet#currentChangeAddress()}, so you must have added at least one key.</p>
     *
     * <p>If you just want to send money quickly, you probably want
     * {@link Wallet#sendCoins(TransactionBroadcaster, Address, Coin)} instead. That will create the sending
     * transaction, commit to the wallet and broadcast it to the network all in one go. This method is lower level
     * and lets you see the proposed transaction before anything is done with it.</p>
     *
     * <p>This is a helper method that is equivalent to using {@link SendRequest#to(Address, Coin)}
     * followed by {@link Wallet#completeTx(SendRequest)} and returning the requests transaction object.
     * Note that this means a fee may be automatically added if required, if you want more control over the process,
     * just do those two steps yourself.</p>
     *
     * <p>IMPORTANT: This method does NOT update the wallet. If you call createSend again you may get two transactions
     * that spend the same coins. You have to call {@link Wallet#commitTx(Transaction)} on the created transaction to
     * prevent this, but that should only occur once the transaction has been accepted by the network. This implies
     * you cannot have more than one outstanding sending tx at once.</p>
     *
     * <p>You MUST ensure that the value is not smaller than {@link TransactionOutput#getMinNonDustValue()} or the transaction
     * will almost certainly be rejected by the network as dust.</p>
     *
     * @param address The Bitcoin address to send the money to.
     * @param value How much currency to send.
     * @param allowUnconfirmed Whether to allow spending unconfirmed outputs.
     * @return either the created Transaction or null if there are insufficient coins.
     * @throws InsufficientMoneyException if the request could not be completed due to not enough balance.
     * @throws DustySendRequested if the resultant transaction would violate the dust rules.
     * @throws CouldNotAdjustDownwards if emptying the wallet was requested and the output can't be shrunk for fees without violating a protocol rule.
     * @throws ExceededMaxTransactionSize if the resultant transaction is too big for Bitcoin to process.
     * @throws MultipleOpReturnRequested if there is more than one OP_RETURN output for the resultant transaction.
     * @throws BadWalletEncryptionKeyException if the supplied {@link SendRequest#aesKey} is wrong.
     */
    public Transaction createSend(Address address, Coin value, boolean allowUnconfirmed)
            throws InsufficientMoneyException, TransactionCompletionException {
        SendRequest req = SendRequest.to(address, value);
        if (allowUnconfirmed)
            req.allowUnconfirmed();
        completeTx(req);
        return req.tx;
    }

    /**
     * Sends coins to the given address but does not broadcast the resulting pending transaction. It is still stored
     * in the wallet, so when the wallet is added to a {@link PeerGroup} or {@link Peer} the transaction will be
     * announced to the network. The given {@link SendRequest} is completed first using
     * {@link Wallet#completeTx(SendRequest)} to make it valid.
     *
     * @return the Transaction that was created
     * @throws InsufficientMoneyException if the request could not be completed due to not enough balance.
     * @throws IllegalArgumentException if you try and complete the same SendRequest twice
     * @throws DustySendRequested if the resultant transaction would violate the dust rules.
     * @throws CouldNotAdjustDownwards if emptying the wallet was requested and the output can't be shrunk for fees without violating a protocol rule.
     * @throws ExceededMaxTransactionSize if the resultant transaction is too big for Bitcoin to process.
     * @throws MultipleOpReturnRequested if there is more than one OP_RETURN output for the resultant transaction.
     * @throws BadWalletEncryptionKeyException if the supplied {@link SendRequest#aesKey} is wrong.
     */
    public Transaction sendCoinsOffline(SendRequest request)
            throws InsufficientMoneyException, TransactionCompletionException {
        lock.lock();
        try {
            completeTx(request);
            commitTx(request.tx);
            return request.tx;
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>Sends coins to the given address, via the given {@link PeerGroup}. Change is returned to
     * {@link Wallet#currentChangeAddress()}. Note that a fee may be automatically added if one may be required for the
     * transaction to be confirmed.</p>
     *
     * <p>The returned object provides both the transaction, and a future that can be used to learn when the broadcast
     * is complete. Complete means, if the PeerGroup is limited to only one connection, when it was written out to
     * the socket. Otherwise when the transaction is written out and we heard it back from a different peer.</p>
     *
     * <p>Note that the sending transaction is committed to the wallet immediately, not when the transaction is
     * successfully broadcast. This means that even if the network hasn't heard about your transaction you won't be
     * able to spend those same coins again.</p>
     *
     * <p>You MUST ensure that value is not smaller than {@link TransactionOutput#getMinNonDustValue()} or the transaction will
     * almost certainly be rejected by the network as dust.</p>
     *
     * @param broadcaster a {@link TransactionBroadcaster} to use to send the transactions out.
     * @param to Which address to send coins to.
     * @param value How much value to send.
     * @return An object containing the transaction that was created, and a future for the broadcast of it.
     * @throws InsufficientMoneyException if the request could not be completed due to not enough balance.
     * @throws DustySendRequested if the resultant transaction would violate the dust rules.
     * @throws CouldNotAdjustDownwards if emptying the wallet was requested and the output can't be shrunk for fees without violating a protocol rule.
     * @throws ExceededMaxTransactionSize if the resultant transaction is too big for Bitcoin to process.
     * @throws MultipleOpReturnRequested if there is more than one OP_RETURN output for the resultant transaction.
     * @throws BadWalletEncryptionKeyException if the supplied {@link SendRequest#aesKey} is wrong.
     */
    public TransactionBroadcast sendCoins(TransactionBroadcaster broadcaster, Address to, Coin value)
            throws InsufficientMoneyException, TransactionCompletionException {
        SendRequest request = SendRequest.to(to, value);
        return sendCoins(broadcaster, request);
    }

    /**
     * <p>Sends coins according to the given request, via the given {@link TransactionBroadcaster}.</p>
     *
     * <p>The returned object provides both the transaction, and a future that can be used to learn when the broadcast
     * is complete. Complete means, if the PeerGroup is limited to only one connection, when it was written out to
     * the socket. Otherwise when the transaction is written out and we heard it back from a different peer.</p>
     *
     * <p>Note that the sending transaction is committed to the wallet immediately, not when the transaction is
     * successfully broadcast. This means that even if the network hasn't heard about your transaction you won't be
     * able to spend those same coins again.</p>
     *
     * @param broadcaster the target to use for broadcast.
     * @param request the SendRequest that describes what to do, get one using static methods on SendRequest itself.
     * @return An object containing the transaction that was created, and a future for the broadcast of it.
     * @throws InsufficientMoneyException if the request could not be completed due to not enough balance.
     * @throws IllegalArgumentException if you try and complete the same SendRequest twice
     * @throws DustySendRequested if the resultant transaction would violate the dust rules.
     * @throws CouldNotAdjustDownwards if emptying the wallet was requested and the output can't be shrunk for fees without violating a protocol rule.
     * @throws ExceededMaxTransactionSize if the resultant transaction is too big for Bitcoin to process.
     * @throws MultipleOpReturnRequested if there is more than one OP_RETURN output for the resultant transaction.
     * @throws BadWalletEncryptionKeyException if the supplied {@link SendRequest#aesKey} is wrong.
     */
    public TransactionBroadcast sendCoins(TransactionBroadcaster broadcaster, SendRequest request)
            throws InsufficientMoneyException, TransactionCompletionException {
        // Should not be locked here, as we're going to call into the broadcaster and that might want to hold its
        // own lock. sendCoinsOffline handles everything that needs to be locked.
        checkState(!lock.isHeldByCurrentThread());

        // Commit the TX to the wallet immediately so the spent coins won't be reused.
        // TODO: We should probably allow the request to specify tx commit only after the network has accepted it.
        Transaction tx = sendCoinsOffline(request);
        TransactionBroadcast result = broadcaster.broadcastTransaction(tx);
        // The tx has been committed to the pending pool by this point (via sendCoinsOffline -> commitTx), so it has
        // a txConfidenceListener registered. Once the tx is broadcast the peers will update the memory pool with the
        // count of seen peers, the memory pool will update the transaction confidence object, that will invoke the
        // txConfidenceListener which will in turn invoke the wallets event listener onTransactionConfidenceChanged
        // method.
        return result;
    }

    /**
     * Satisfies the given {@link SendRequest} using the default transaction broadcaster configured either via
     * {@link PeerGroup#addWallet(Wallet)} or directly with {@link #setTransactionBroadcaster(TransactionBroadcaster)}.
     *
     * @param request the SendRequest that describes what to do, get one using static methods on SendRequest itself.
     * @return An object containing the transaction that was created, and a future for the broadcast of it.
     * @throws IllegalStateException if no transaction broadcaster has been configured.
     * @throws InsufficientMoneyException if the request could not be completed due to not enough balance.
     * @throws IllegalArgumentException if you try and complete the same SendRequest twice
     * @throws DustySendRequested if the resultant transaction would violate the dust rules.
     * @throws CouldNotAdjustDownwards if emptying the wallet was requested and the output can't be shrunk for fees without violating a protocol rule.
     * @throws ExceededMaxTransactionSize if the resultant transaction is too big for Bitcoin to process.
     * @throws MultipleOpReturnRequested if there is more than one OP_RETURN output for the resultant transaction.
     * @throws BadWalletEncryptionKeyException if the supplied {@link SendRequest#aesKey} is wrong.
     */
    public TransactionBroadcast sendCoins(SendRequest request)
            throws InsufficientMoneyException, TransactionCompletionException {
        TransactionBroadcaster broadcaster = vTransactionBroadcaster;
        checkState(broadcaster != null, () ->
                "no transaction broadcaster is configured");
        return sendCoins(broadcaster, request);
    }

    /**
     * Sends coins to the given address, via the given {@link Peer}. Change is returned to {@link Wallet#currentChangeAddress()}.
     * If an exception is thrown by {@link Peer#sendMessage(Message)} the transaction is still committed, so the
     * pending transaction must be broadcast <b>by you</b> at some other time. Note that a fee may be automatically added
     * if one may be required for the transaction to be confirmed.
     *
     * @return The {@link Transaction} that was created or null if there was insufficient balance to send the coins.
     * @throws InsufficientMoneyException if the request could not be completed due to not enough balance.
     * @throws IllegalArgumentException if you try and complete the same SendRequest twice
     * @throws DustySendRequested if the resultant transaction would violate the dust rules.
     * @throws CouldNotAdjustDownwards if emptying the wallet was requested and the output can't be shrunk for fees without violating a protocol rule.
     * @throws ExceededMaxTransactionSize if the resultant transaction is too big for Bitcoin to process.
     * @throws MultipleOpReturnRequested if there is more than one OP_RETURN output for the resultant transaction.
     * @throws BadWalletEncryptionKeyException if the supplied {@link SendRequest#aesKey} is wrong.
     */
    public Transaction sendCoins(Peer peer, SendRequest request)
            throws InsufficientMoneyException, TransactionCompletionException {
        Transaction tx = sendCoinsOffline(request);
        peer.sendMessage(tx);
        return tx;
    }

    /**
     * Initiate sending the transaction in a {@link SendRequest}. Calls {@link Wallet#sendCoins(SendRequest)} which
     * performs the following significant operations internally:
     * <ol>
     *     <li>{@link Wallet#completeTx(SendRequest)} -- calculate change and sign</li>
     *     <li>{@link Wallet#commitTx(Transaction)} -- puts the transaction in the {@code Wallet}'s pending pool</li>
     *     <li>{@link org.bitcoinj.core.TransactionBroadcaster#broadcastTransaction(Transaction)} typically implemented by {@link org.bitcoinj.core.PeerGroup#broadcastTransaction(Transaction)} -- queues requests to send the transaction to a single remote {@code Peer}</li>
     * </ol>
     * This method will <i>complete</i> and return a {@link TransactionBroadcast} when the send to the remote peer occurs (is buffered.)
     * The broadcast process includes the following steps:
     * <ol>
     *     <li>Wait until enough {@link org.bitcoinj.core.Peer}s are connected.</li>
     *     <li>Broadcast (buffer for send) the transaction to a single remote {@link org.bitcoinj.core.Peer}</li>
     *     <li>Mark {@link TransactionBroadcast#awaitSent()} as complete</li>
     *     <li>Wait for a number of remote peers to confirm they have received the broadcast</li>
     *     <li>Mark {@link TransactionBroadcast#awaitRelayed()} as complete</li>
     * </ol>
     * @param sendRequest transaction to send
     * @return A future for the transaction broadcast
     */
    public CompletableFuture<TransactionBroadcast> sendTransaction(SendRequest sendRequest) {
        try {
            // Complete successfully when the transaction has been sent (or buffered, at least) to peers.
            return sendCoins(sendRequest).awaitSent();
        } catch (KeyCrypterException | InsufficientMoneyException e) {
            // We should never try to send more coins than we have, if we do we get an InsufficientMoneyException
            return FutureUtils.failedFuture(e);
        }
    }

    /**
     * Wait for at least 1 confirmation on a transaction.
     * @param tx the transaction we are waiting for
     * @return a future for an object that contains transaction confidence information
     */
    public CompletableFuture<TransactionConfidence> waitForConfirmation(Transaction tx) {
        return waitForConfirmations(tx, 1);
    }

    /**
     * Wait for a required number of confirmations on a transaction.
     * @param tx the transaction we are waiting for
     * @param requiredConfirmations the minimum required confirmations before completing
     * @return a future for an object that contains transaction confidence information
     */
    public CompletableFuture<TransactionConfidence> waitForConfirmations(Transaction tx, int requiredConfirmations) {

        return getConfidence(tx).getDepthFuture(requiredConfirmations);
    }

    TransactionConfidence getConfidence(Transaction tx) {
        return Context.get().getConfidenceTable().getConfidence(tx);
    }

    /**
     * Class of exceptions thrown in {@link Wallet#completeTx(SendRequest)}.
     */
    public static class TransactionCompletionException extends RuntimeException {
        public TransactionCompletionException() {
            super();
        }

        public TransactionCompletionException(Throwable throwable) {
            super(throwable);
        }
        public TransactionCompletionException(String message) {
            super(message);
        }
    }
    /**
     * Thrown if the resultant transaction would violate the dust rules (an output that's too small to be worthwhile).
     */
    public static class DustySendRequested extends TransactionCompletionException {}
    /**
     * Thrown if there is more than one OP_RETURN output for the resultant transaction.
     */
    public static class MultipleOpReturnRequested extends TransactionCompletionException {}
    /**
     * Thrown when we were trying to empty the wallet, and the total amount of money we were trying to empty after
     * being reduced for the fee was smaller than the min payment. Note that the missing field will be null in this
     * case.
     */
    public static class CouldNotAdjustDownwards extends TransactionCompletionException {
        CouldNotAdjustDownwards() {
            super();
        }
        CouldNotAdjustDownwards(Coin value, Coin nonDustAmout) {
            super(String.format("Value %s is below non-dust threshold of %s", value.toFriendlyString(), nonDustAmout.toFriendlyString()));
        }
    }
    /**
     * Thrown if the resultant transaction is too big for Bitcoin to process. Try breaking up the amounts of value.
     */
    public static class ExceededMaxTransactionSize extends TransactionCompletionException {}
    /**
     * Thrown if the private keys and seed of this wallet cannot be decrypted due to the supplied encryption
     * key or password being wrong.
     */
    public static class BadWalletEncryptionKeyException extends TransactionCompletionException {
        public BadWalletEncryptionKeyException(Throwable throwable) {
            super(throwable);
        }
    }

    /**
     * Given a spend request containing an incomplete transaction, makes it valid by adding outputs and signed inputs
     * according to the instructions in the request. The transaction in the request is modified by this method.
     *
     * @param req a SendRequest that contains the incomplete transaction and details for how to make it valid.
     * @throws InsufficientMoneyException if the request could not be completed due to not enough balance.
     * @throws IllegalArgumentException if you try and complete the same SendRequest twice
     * @throws DustySendRequested if the resultant transaction would violate the dust rules.
     * @throws CouldNotAdjustDownwards if emptying the wallet was requested and the output can't be shrunk for fees without violating a protocol rule.
     * @throws ExceededMaxTransactionSize if the resultant transaction is too big for Bitcoin to process.
     * @throws MultipleOpReturnRequested if there is more than one OP_RETURN output for the resultant transaction.
     * @throws BadWalletEncryptionKeyException if the supplied {@link SendRequest#aesKey} is wrong.
     */
    public void completeTx(SendRequest req) throws InsufficientMoneyException, TransactionCompletionException {
        lock.lock();
        try {
            checkArgument(!req.completed, () ->
                    "given SendRequest has already been completed");

            log.info("Completing send tx with {} outputs totalling {} and a fee of {}/vkB", req.tx.getOutputs().size(),
                    req.tx.getOutputSum().toFriendlyString(), req.feePerKb.toFriendlyString());

            // Calculate a list of ALL potential candidates for spending and then ask a coin selector to provide us
            // with the actual outputs that'll be used to gather the required amount of value. In this way, users
            // can customize coin selection policies. The call below will ignore immature coinbases and outputs
            // we don't have the keys for.
            List<TransactionOutput> prelimCandidates = calculateAllSpendCandidates(true, req.missingSigsMode == MissingSigsMode.THROW);

            // Connect (add a value amount) unconnected inputs
            List<TransactionInput> inputs = connectInputs(prelimCandidates, req.tx.getInputs());
            req.tx.clearInputs();
            inputs.forEach(req.tx::addInput);

            // Warn if there are remaining unconnected inputs whose value we do not know
            // TODO: Consider throwing if there are inputs that we don't have a value for
            if (req.tx.getInputs().stream()
                    .map(TransactionInput::getValue)
                    .anyMatch(Objects::isNull))
                log.warn("SendRequest transaction already has inputs but we don't know how much they are worth - they will be added to fee.");

            // If any inputs have already been added, we don't need to get their value from wallet
            Coin totalInput = req.tx.getInputSum();
            // Calculate the amount of value we need to import.
            Coin valueNeeded = req.tx.getOutputSum().subtract(totalInput);

            // Enforce the OP_RETURN limit
            if (req.tx.getOutputs().stream()
                    .filter(o -> ScriptPattern.isOpReturn(o.getScriptPubKey()))
                    .count() > 1) // Only 1 OP_RETURN per transaction allowed.
                throw new MultipleOpReturnRequested();

            // Check for dusty sends
            if (req.ensureMinRequiredFee && !req.emptyWallet) { // Min fee checking is handled later for emptyWallet.
                if (req.tx.getOutputs().stream().anyMatch(TransactionOutput::isDust))
                    throw new DustySendRequested();
            }

            // Filter out candidates that are already included in the transaction inputs
            List<TransactionOutput> candidates = prelimCandidates.stream()
                    .filter(output -> alreadyIncluded(req.tx.getInputs(), output))
                    .collect(StreamUtils.toUnmodifiableList());

            CoinSelection bestCoinSelection;
            TransactionOutput bestChangeOutput = null;
            List<Coin> updatedOutputValues = null;
            if (!req.emptyWallet) {
                // This can throw InsufficientMoneyException.
                FeeCalculation feeCalculation = calculateFee(req, valueNeeded, req.ensureMinRequiredFee, candidates);
                bestCoinSelection = feeCalculation.bestCoinSelection;
                bestChangeOutput = feeCalculation.bestChangeOutput;
                updatedOutputValues = feeCalculation.updatedOutputValues;
            } else {
                // We're being asked to empty the wallet. What this means is ensuring "tx" has only a single output
                // of the total value we can currently spend as determined by the selector, and then subtracting the fee.
                checkState(req.tx.getOutputs().size() == 1, () ->
                        "empty wallet TX must have a single output only");
                CoinSelector selector = req.coinSelector == null ? coinSelector : req.coinSelector;
                bestCoinSelection = selector.select((Coin) network.maxMoney(), candidates);
                candidates = null;  // Selector took ownership and might have changed candidates. Don't access again.
                req.tx.replaceOutput(0, req.tx.getOutput(0).withValue(bestCoinSelection.totalValue()));
                log.info("  emptying {}", bestCoinSelection.totalValue().toFriendlyString());
            }

            bestCoinSelection.outputs()
                    .forEach(req.tx::addInput);

            if (req.emptyWallet) {
                if (!adjustOutputDownwardsForFee(req.tx, bestCoinSelection, req.feePerKb, req.ensureMinRequiredFee))
                    throw new CouldNotAdjustDownwards();
            }

            if (updatedOutputValues != null) {
                for (int i = 0; i < updatedOutputValues.size(); i++) {
                    req.tx.replaceOutput(i, req.tx.getOutput(i).withValue(updatedOutputValues.get(i)));
                }
            }

            if (bestChangeOutput != null) {
                req.tx.addOutput(bestChangeOutput);
                log.info("  with {} change", bestChangeOutput.getValue().toFriendlyString());
            }

            // Now shuffle the outputs to obfuscate which is the change.
            if (req.shuffleOutputs)
                req.tx.shuffleOutputs();

            // Now sign the inputs, thus proving that we are entitled to redeem the connected outputs.
            if (req.signInputs)
                signTransaction(req);

            // Check size.
            final int size = req.tx.messageSize();
            if (size > Transaction.MAX_STANDARD_TX_SIZE)
                throw new ExceededMaxTransactionSize();

            // Label the transaction as being self created. We can use this later to spend its change output even before
            // the transaction is confirmed. We deliberately won't bother notifying listeners here as there's not much
            // point - the user isn't interested in a confidence transition they made themselves.
            getConfidence(req.tx).setSource(TransactionConfidence.Source.SELF);
            // Label the transaction as being a user requested payment. This can be used to render GUI wallet
            // transaction lists more appropriately, especially when the wallet starts to generate transactions itself
            // for internal purposes.
            req.tx.setPurpose(Transaction.Purpose.USER_PAYMENT);
            // Record the exchange rate that was valid when the transaction was completed.
            req.tx.setExchangeRate(req.exchangeRate);
            req.tx.setMemo(req.memo);
            req.completed = true;
            log.info("  completed: {}", req.tx);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Connect unconnected inputs with outputs from the wallet
     * @param candidates A list of spend candidates from a Wallet
     * @param inputs a list of possibly unconnected/unvalued inputs (e.g. from a spend request)
     * @return a list of the same inputs, but connected/valued if not previously valued and found in wallet
     */
    // For testing only
    static List<TransactionInput> connectInputs(List<TransactionOutput> candidates, List<TransactionInput> inputs) {
        return inputs.stream()
                .map(in -> candidates.stream()
                        .filter(utxo -> utxo.getOutPointFor().equals(in.getOutpoint()))
                        .findFirst()
                        .map(o -> new TransactionInput(o.getParentTransaction(), o.getScriptPubKey().program(), o.getOutPointFor(), o.getValue()))
                        .orElse(in))
                .collect(StreamUtils.toUnmodifiableList());
    }

    /**
     * Is a UTXO already included (to be spent) in a list of transaction inputs?
     * @param inputs the list of inputs to check
     * @param output the transaction output
     * @return true if it is already included, false otherwise
     */
    private boolean alreadyIncluded(List<TransactionInput> inputs, TransactionOutput output) {
        return inputs.stream().noneMatch(i -> i.getOutpoint().equals(output.getOutPointFor()));
    }

    /**
     * <p>Given a send request containing transaction, attempts to sign it's inputs. This method expects transaction
     * to have all necessary inputs connected or they will be ignored.</p>
     * <p>Actual signing is done by pluggable {@link #signers} and it's not guaranteed that
     * transaction will be complete in the end.</p>
     * @throws BadWalletEncryptionKeyException if the supplied {@link SendRequest#aesKey} is wrong.
     */
    public void signTransaction(SendRequest req) throws BadWalletEncryptionKeyException {
        lock.lock();
        try {
            Transaction tx = req.tx;
            List<TransactionInput> inputs = tx.getInputs();
            List<TransactionOutput> outputs = tx.getOutputs();
            checkState(inputs.size() > 0);
            checkState(outputs.size() > 0);

            KeyBag maybeDecryptingKeyBag = new DecryptingKeyBag(this, req.aesKey);

            int numInputs = tx.getInputs().size();
            for (int i = 0; i < numInputs; i++) {
                TransactionInput txIn = tx.getInput(i);
                TransactionOutput connectedOutput = txIn.getConnectedOutput();
                if (connectedOutput == null) {
                    // Missing connected output, assuming already signed.
                    continue;
                }
                Script scriptPubKey = connectedOutput.getScriptPubKey();

                try {
                    // We assume if its already signed, its hopefully got a SIGHASH type that will not invalidate when
                    // we sign missing pieces (to check this would require either assuming any signatures are signing
                    // standard output types or a way to get processed signatures out of script execution)
                    ScriptExecution.correctlySpends(txIn.getScriptSig(), tx, i, txIn.getWitness(), connectedOutput.getValue(),
                            connectedOutput.getScriptPubKey(), ScriptExecution.ALL_VERIFY_FLAGS);
                    log.warn("Input {} already correctly spends output, assuming SIGHASH type used will be safe and skipping signing.", i);
                    continue;
                } catch (ScriptException e) {
                    log.debug("Input contained an incorrect signature", e);
                    // Expected.
                }

                RedeemData redeemData = txIn.getConnectedRedeemData(maybeDecryptingKeyBag);
                Objects.requireNonNull(redeemData, () ->
                        "Transaction exists in wallet that we cannot redeem: " + txIn.getOutpoint().hash());
                tx.replaceInput(i, txIn.withScriptSig(scriptPubKey.createEmptyInputScript(redeemData.keys.get(0),
                        redeemData.redeemScript)));
            }

            TransactionSigner.ProposedTransaction proposal = new TransactionSigner.ProposedTransaction(tx);
            for (TransactionSigner signer : signers) {
                if (!signer.signInputs(proposal, maybeDecryptingKeyBag))
                    log.info("{} returned false for the tx", signer.getClass().getName());
            }

            // resolve missing sigs if any
            new MissingSigResolutionSigner(req.missingSigsMode).signInputs(proposal, maybeDecryptingKeyBag);
        } catch (KeyCrypterException.InvalidCipherText | KeyCrypterException.PublicPrivateMismatch e) {
            throw new BadWalletEncryptionKeyException(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reduce the value of the first output of a transaction to pay the given feePerKb as appropriate for its size.
     * If ensureMinRequiredFee is true, feePerKb is set to at least {@link Transaction#REFERENCE_DEFAULT_MIN_TX_FEE}.
     * @return true if output is not dust
     */
    private boolean adjustOutputDownwardsForFee(Transaction tx, CoinSelection coinSelection, Coin feePerKb,
            boolean ensureMinRequiredFee) {
        Coin fee = estimateFees(tx, coinSelection, feePerKb, ensureMinRequiredFee);
        TransactionOutput output = tx.getOutput(0);
        output = output.withValue(output.getValue().subtract(fee));
        tx.replaceOutput(0, output);
        return !output.isDust();
    }

    /**
     * Returns a list of the outputs that can potentially be spent, i.e. that we have the keys for and are unspent
     * according to our knowledge of the block chain.
     */
    public List<TransactionOutput> calculateAllSpendCandidates() {
        return calculateAllSpendCandidates(true, true);
    }

    /**
     * Returns a list of all outputs that are being tracked by this wallet either from the {@link UTXOProvider}
     * (in this case the existence or not of private keys is ignored), or the wallets internal storage (the default)
     * taking into account the flags.
     *
     * @param excludeImmatureCoinbases Whether to ignore coinbase outputs that we will be able to spend in future once they mature.
     * @param excludeUnsignable Whether to ignore outputs that we are tracking but don't have the keys to sign for.
     */
    public List<TransactionOutput> calculateAllSpendCandidates(boolean excludeImmatureCoinbases, boolean excludeUnsignable) {
        lock.lock();
        try {
            List<TransactionOutput> candidates;
            if (vUTXOProvider == null) {
                candidates = myUnspents.stream()
                    .filter(output ->   (!excludeUnsignable || canSignFor(output.getScriptPubKey())) &&
                                        (!excludeImmatureCoinbases || isTransactionMature(output.getParentTransaction())))
                    .collect(StreamUtils.toUnmodifiableList());
            } else {
                candidates = calculateAllSpendCandidatesFromUTXOProviderInternal(excludeImmatureCoinbases);
            }
            return candidates;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns true if this wallet has at least one of the private keys needed to sign for this scriptPubKey. Returns
     * false if the form of the script is not known or if the script is OP_RETURN.
     */
    public boolean canSignFor(Script script) {
        if (ScriptPattern.isP2PK(script)) {
            byte[] pubkey = ScriptPattern.extractKeyFromP2PK(script);
            ECKey key = findKeyFromPubKey(pubkey);
            return key != null && (key.isEncrypted() || key.hasPrivKey());
        } else if (ScriptPattern.isP2SH(script)) {
            RedeemData data = findRedeemDataFromScriptHash(ScriptPattern.extractHashFromP2SH(script));
            return data != null && canSignFor(data.redeemScript);
        } else if (ScriptPattern.isP2PKH(script)) {
            ECKey key = findKeyFromPubKeyHash(ScriptPattern.extractHashFromP2PKH(script),
                    ScriptType.P2PKH);
            return key != null && (key.isEncrypted() || key.hasPrivKey());
        } else if (ScriptPattern.isP2WPKH(script)) {
            ECKey key = findKeyFromPubKeyHash(ScriptPattern.extractHashFromP2WH(script),
                    ScriptType.P2WPKH);
            return key != null && (key.isEncrypted() || key.hasPrivKey()) && key.isCompressed();
        } else if (ScriptPattern.isSentToMultisig(script)) {
            for (ECKey pubkey : script.getPubKeys()) {
                ECKey key = findKeyFromPubKey(pubkey.getPubKey());
                if (key != null && (key.isEncrypted() || key.hasPrivKey()))
                    return true;
            }
        }
        return false;
    }

    /**
     * Returns the spendable candidates from the {@link UTXOProvider} based on keys that the wallet contains.
     * @return The list of candidates.
     */
    @Deprecated
    protected List<TransactionOutput> calculateAllSpendCandidatesFromUTXOProvider(boolean excludeImmatureCoinbases) {
        return calculateAllSpendCandidatesFromUTXOProviderInternal(excludeImmatureCoinbases);
    }

    private List<TransactionOutput> calculateAllSpendCandidatesFromUTXOProviderInternal(boolean excludeImmatureCoinbases) {
        checkState(lock.isHeldByCurrentThread());
        UTXOProvider utxoProvider = Objects.requireNonNull(vUTXOProvider, "No UTXO provider has been set");
        List<TransactionOutput> candidates = new LinkedList<>();
        try {
            int chainHeight = utxoProvider.getChainHeadHeight();
            for (UTXO output : getStoredOutputsFromUTXOProviderInternal()) {
                boolean coinbase = output.isCoinbase();
                int depth = chainHeight - output.getHeight() + 1; // the current depth of the output (1 = same as head).
                // Do not try and spend coinbases that were mined too recently, the protocol forbids it.
                if (!excludeImmatureCoinbases || !coinbase || depth >= params.getSpendableCoinbaseDepth()) {
                    candidates.add(new FreeStandingTransactionOutput(output, chainHeight));
                }
            }
        } catch (UTXOProviderException e) {
            throw new RuntimeException("UTXO provider error", e);
        }

        // We need to handle the pending transactions that we know about.
        for (Transaction tx : pending.values()) {
            // Remove the spent outputs.
            for (TransactionInput input : tx.getInputs()) {
                if (input.getConnectedOutput().isMine(this)) {
                    candidates.remove(input.getConnectedOutput());
                }
            }
            // Add change outputs. Do not try and spend coinbases that were mined too recently, the protocol forbids it.
            if (!excludeImmatureCoinbases || isTransactionMature(tx)) {
                for (TransactionOutput output : tx.getOutputs()) {
                    if (output.isAvailableForSpending() && output.isMine(this)) {
                        candidates.add(output);
                    }
                }
            }
        }
        return candidates;
    }

    /**
     * Get all the {@link UTXO}'s from the {@link UTXOProvider} based on keys that the
     * wallet contains.
     * @return The list of stored outputs.
     */
    @Deprecated
    protected List<UTXO> getStoredOutputsFromUTXOProvider() throws UTXOProviderException {
        return getStoredOutputsFromUTXOProviderInternal();
    }

    private List<UTXO> getStoredOutputsFromUTXOProviderInternal() throws UTXOProviderException {
        UTXOProvider utxoProvider = Objects.requireNonNull(vUTXOProvider, "No UTXO provider has been set");
        List<UTXO> candidates = new ArrayList<>();
        List<ECKey> keys = getImportedKeys();
        keys.addAll(getActiveKeyChain().getLeafKeys());
        candidates.addAll(utxoProvider.getOpenTransactionOutputs(keys));
        return candidates;
    }

    /** Returns the default {@link CoinSelector} object that is used by this wallet if no custom selector is specified. */
    public CoinSelector getCoinSelector() {
        lock.lock();
        try {
            return coinSelector;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the {@link UTXOProvider}.
     * @return The UTXO provider.
     * @deprecated Use a UTXOProvider separate from the wallet if you want to search an external source for UTXOs.
     */
    @Deprecated
    @Nullable public UTXOProvider getUTXOProvider() {
        lock.lock();
        try {
            return vUTXOProvider;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Set the {@link UTXOProvider}.
     *
     * <p>The wallet will query the provider for spendable candidates, i.e. outputs controlled exclusively
     * by private keys contained in the wallet.</p>
     *
     * <p>Note that the associated provider must be reattached after a wallet is loaded from disk.
     * The association is not serialized.</p>
     * @deprecated Use a UTXOProvider separate from the wallet if you want to search an external source for UTXOs.
     */
    @Deprecated
    public void setUTXOProvider(@Nullable UTXOProvider provider) {
        setUTXOProviderInternal(provider);
    }

    @VisibleForTesting
    public void setUTXOProviderInternal(@Nullable UTXOProvider provider) {
        lock.lock();
        try {
            checkArgument(provider == null || provider.network() == network);
            this.vUTXOProvider = provider;
        } finally {
            lock.unlock();
        }
    }

    //endregion

    // ***************************************************************************************************************
    /**
     * A custom {@link TransactionOutput} that is freestanding. This contains all the information
     * required for spending without actually having all the linked data (i.e parent tx).
     *
     */
    private static class FreeStandingTransactionOutput extends TransactionOutput {
        private final UTXO output;
        private final int chainHeight;

        /**
         * Construct a freestanding Transaction Output.
         * @param output The stored output (freestanding).
         */
        public FreeStandingTransactionOutput(UTXO output, int chainHeight) {
            super(null, output.getValue(), output.getScript().program());
            this.output = output;
            this.chainHeight = chainHeight;
        }

        /**
         * Get the {@link UTXO}.
         * @return The stored output.
         */
        public UTXO getUTXO() {
            return output;
        }

        /**
         * Get the depth withing the chain of the parent tx, depth is 1 if it the output height is the height of
         * the latest block.
         * @return The depth.
         */
        @Override
        public int getParentTransactionDepthInBlocks() {
            return chainHeight - output.getHeight() + 1;
        }

        @Override
        public int getIndex() {
            return (int) output.getIndex();
        }

        @Override
        public Sha256Hash getParentTransactionHash() {
            return output.getHash();
        }
    }

    // ***************************************************************************************************************

    private static class TxOffsetPair implements Comparable<TxOffsetPair> {
        public final Transaction tx;
        public final int offset;

        public TxOffsetPair(Transaction tx, int offset) {
            this.tx = tx;
            this.offset = offset;
        }

        @Override public int compareTo(TxOffsetPair o) {
            // note that in this implementation compareTo() is not consistent with equals()
            return Integer.compare(offset, o.offset);
        }
    }

    //region Reorganisations

    /**
     * <p>Don't call this directly. It's not intended for API users.</p>
     *
     * <p>Called by the {@link BlockChain} when the best chain (representing total work done) has changed. This can
     * cause the number of confirmations of a transaction to go higher, lower, drop to zero and can even result in
     * a transaction going dead (will never confirm) due to a double spend.</p>
     *
     * <p>The oldBlocks/newBlocks lists are ordered height-wise from top first to bottom last.</p>
     */
    @Override
    public void reorganize(StoredBlock splitPoint, List<StoredBlock> oldBlocks, List<StoredBlock> newBlocks) throws VerificationException {
        lock.lock();
        try {
            // This runs on any peer thread with the block chain locked.
            //
            // The reorganize functionality of the wallet is tested in ChainSplitTest.java
            //
            // receive() has been called on the block that is triggering the re-org before this is called, with type
            // of SIDE_CHAIN.
            //
            // Note that this code assumes blocks are not invalid - if blocks contain duplicated transactions,
            // transactions that double spend etc then we can calculate the incorrect result. This could open up
            // obscure DoS attacks if someone successfully mines a throwaway invalid block and feeds it to us, just
            // to try and corrupt the internal data structures. We should try harder to avoid this but it's tricky
            // because there are so many ways the block can be invalid.

            // Avoid spuriously informing the user of wallet/tx confidence changes whilst we're re-organizing.
            checkState(confidenceChanged.isEmpty());
            checkState(!insideReorg);
            insideReorg = true;
            checkState(onWalletChangedSuppressions == 0);
            onWalletChangedSuppressions++;

            // Map block hash to transactions that appear in it. We ensure that the map values are sorted according
            // to their relative position within those blocks.
            ArrayListMultimap<Sha256Hash, TxOffsetPair> mapBlockTx = ArrayListMultimap.create();
            for (Transaction tx : getTransactions(true)) {
                Map<Sha256Hash, Integer> appearsIn = tx.getAppearsInHashes();
                if (appearsIn == null) continue;  // Pending.
                for (Map.Entry<Sha256Hash, Integer> block : appearsIn.entrySet())
                    mapBlockTx.put(block.getKey(), new TxOffsetPair(tx, block.getValue()));
            }
            for (Sha256Hash blockHash : mapBlockTx.keySet())
                Collections.sort(mapBlockTx.get(blockHash));

            List<Sha256Hash> oldBlockHashes = new ArrayList<>(oldBlocks.size());
            log.info("Old part of chain (top to bottom):");
            for (StoredBlock b : oldBlocks) {
                log.info("  {}", b.getHeader().getHashAsString());
                oldBlockHashes.add(b.getHeader().getHash());
            }
            log.info("New part of chain (top to bottom):");
            for (StoredBlock b : newBlocks) {
                log.info("  {}", b.getHeader().getHashAsString());
            }

            Collections.reverse(newBlocks);  // Need bottom-to-top but we get top-to-bottom.

            // For each block in the old chain, disconnect the transactions in reverse order.
            List<Transaction> oldChainTxns = new LinkedList<>();
            for (Sha256Hash blockHash : oldBlockHashes) {
                for (TxOffsetPair pair : mapBlockTx.get(blockHash)) {
                    Transaction tx = pair.tx;
                    final Sha256Hash txHash = tx.getTxId();
                    if (tx.isCoinBase()) {
                        // All the transactions that we have in our wallet which spent this coinbase are now invalid
                        // and will never confirm. Hopefully this should never happen - that's the point of the maturity
                        // rule that forbids spending of coinbase transactions for 100 blocks.
                        //
                        // This could be recursive, although of course because we don't have the full transaction
                        // graph we can never reliably kill all transactions we might have that were rooted in
                        // this coinbase tx. Some can just go pending forever, like the Bitcoin Core. However we
                        // can do our best.
                        log.warn("Coinbase killed by re-org: {}", tx.getTxId());
                        killTxns(Collections.singleton(tx), null);
                    } else {
                        for (TransactionOutput output : tx.getOutputs()) {
                            TransactionInput input = output.getSpentBy();
                            if (input != null) {
                                if (output.isMineOrWatched(this))
                                    checkState(myUnspents.add(output));
                                input.disconnect();
                            }
                        }
                        oldChainTxns.add(tx);
                        unspent.remove(txHash);
                        spent.remove(txHash);
                        checkState(!pending.containsKey(txHash));
                        checkState(!dead.containsKey(txHash));
                    }
                }
            }

            // Put all the disconnected transactions back into the pending pool and re-connect them.
            for (Transaction tx : oldChainTxns) {
                // Coinbase transactions on the old part of the chain are dead for good and won't come back unless
                // there's another re-org.
                if (tx.isCoinBase()) continue;
                log.info("  ->pending {}", tx.getTxId());

                getConfidence(tx).setConfidenceType(ConfidenceType.PENDING);  // Wipe height/depth/work data.
                confidenceChanged.put(tx, TransactionConfidence.Listener.ChangeReason.TYPE);
                addWalletTransaction(Pool.PENDING, tx);
                updateForSpends(tx, false);
            }

            // Note that dead transactions stay dead. Consider a chain that Finney attacks T1 and replaces it with
            // T2, so we move T1 into the dead pool. If there's now a re-org to a chain that doesn't include T2, it
            // doesn't matter - the miners deleted T1 from their mempool, will resurrect T2 and put that into the
            // mempool and so T1 is still seen as a losing double spend.

            // The old blocks have contributed to the depth for all the transactions in the
            // wallet that are in blocks up to and including the chain split block.
            // The total depth is calculated here and then subtracted from the appropriate transactions.
            int depthToSubtract = oldBlocks.size();
            log.info("depthToSubtract = " + depthToSubtract);
            // Remove depthToSubtract from all transactions in the wallet except for pending.
            subtractDepth(depthToSubtract, spent.values());
            subtractDepth(depthToSubtract, unspent.values());
            subtractDepth(depthToSubtract, dead.values());

            // The effective last seen block is now the split point so set the lastSeenBlockHash.
            setLastBlockSeenHash(splitPoint.getHeader().getHash());

            // For each block in the new chain, work forwards calling receive() and notifyNewBestBlock().
            // This will pull them back out of the pending pool, or if the tx didn't appear in the old chain and
            // does appear in the new chain, will treat it as such and possibly kill pending transactions that
            // conflict.
            for (StoredBlock block : newBlocks) {
                log.info("Replaying block {}", block.getHeader().getHashAsString());
                for (TxOffsetPair pair : mapBlockTx.get(block.getHeader().getHash())) {
                    log.info("  tx {}", pair.tx.getTxId());
                    try {
                        receive(pair.tx, block, BlockChain.NewBlockType.BEST_CHAIN, pair.offset);
                    } catch (ScriptException e) {
                        throw new RuntimeException(e);  // Cannot happen as these blocks were already verified.
                    }
                }
                notifyNewBestBlock(block);
            }
            isConsistentOrThrow();
            final Coin balance = getBalance();
            log.info("post-reorg balance is {}", balance.toFriendlyString());
            // Inform event listeners that a re-org took place.
            queueOnReorganize();
            insideReorg = false;
            onWalletChangedSuppressions--;
            maybeQueueOnWalletChanged();
            checkBalanceFuturesLocked();
            informConfidenceListenersIfNotReorganizing();
            saveLater();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Subtract the supplied depth from the given transactions.
     */
    private void subtractDepth(int depthToSubtract, Collection<Transaction> transactions) {
        for (Transaction tx : transactions) {
            if (getConfidence(tx).getConfidenceType() == ConfidenceType.BUILDING) {
                getConfidence(tx).setDepthInBlocks(getConfidence(tx).getDepthInBlocks() - depthToSubtract);
                confidenceChanged.put(tx, TransactionConfidence.Listener.ChangeReason.DEPTH);
            }
        }
    }

    //endregion

    /******************************************************************************************************************/

    //region Bloom filtering

    private final List<TransactionOutPoint> bloomOutPoints = new ArrayList<>();
    // Used to track whether we must automatically begin/end a filter calculation and calc outpoints/take the locks.
    private final AtomicInteger bloomFilterGuard = new AtomicInteger(0);

    @Override
    public void beginBloomFilterCalculation() {
        if (bloomFilterGuard.incrementAndGet() > 1)
            return;
        lock.lock();
        keyChainGroupLock.lock();
        //noinspection FieldAccessNotGuarded
        calcBloomOutPointsLocked();
    }

    private void calcBloomOutPointsLocked() {
        // TODO: This could be done once and then kept up to date.
        bloomOutPoints.clear();
        // Search unspent, spent, and pending for all TransactionOutputs that are bloom filterable and
        // then collect a list of the corresponding TransactionOutPoints.
        List<TransactionOutPoint> outPoints = Stream.of(unspent.values(), spent.values(), pending.values())
                .flatMap(Collection::stream)
                .flatMap(tx -> tx.getOutputs().stream())
                .filter(this::isTxOutputBloomFilterable)
                .map(TransactionOutput::getOutPointFor)
                .collect(Collectors.toList());
        bloomOutPoints.addAll(outPoints);
    }

    @Override @GuardedBy("keyChainGroupLock")
    public void endBloomFilterCalculation() {
        if (bloomFilterGuard.decrementAndGet() > 0)
            return;
        bloomOutPoints.clear();
        keyChainGroupLock.unlock();
        lock.unlock();
    }

    /**
     * Returns the number of distinct data items (note: NOT keys) that will be inserted into a bloom filter, when it
     * is constructed.
     */
    @Override
    public int getBloomFilterElementCount() {
        beginBloomFilterCalculation();
        try {
            int size = bloomOutPoints.size();
            size += keyChainGroup.getBloomFilterElementCount();
            // Some scripts may have more than one bloom element.  That should normally be okay, because under-counting
            // just increases false-positive rate.
            size += watchedScripts.size();
            return size;
        } finally {
            endBloomFilterCalculation();
        }
    }

    /**
     * Gets a bloom filter that contains all of the public keys from this wallet, and which will provide the given
     * false-positive rate. See the docs for {@link BloomFilter} for a brief explanation of anonymity when using filters.
     */
    public BloomFilter getBloomFilter(double falsePositiveRate) {
        beginBloomFilterCalculation();
        try {
            return getBloomFilter(getBloomFilterElementCount(), falsePositiveRate, new Random().nextInt());
        } finally {
            endBloomFilterCalculation();
        }
    }

    /**
     * <p>Gets a bloom filter that contains all of the public keys from this wallet, and which will provide the given
     * false-positive rate if it has size elements. Keep in mind that you will get 2 elements in the bloom filter for
     * each key in the wallet, for the public key and the hash of the public key (address form).</p>
     * 
     * <p>This is used to generate a BloomFilter which can be {@link BloomFilter#merge(BloomFilter)}d with another.
     * It could also be used if you have a specific target for the filter's size.</p>
     * 
     * <p>See the docs for {@link BloomFilter#BloomFilter(int, double, int, BloomFilter.BloomUpdate)} for a brief explanation of anonymity when using bloom
     * filters.</p>
     */
    @Override @GuardedBy("keyChainGroupLock")
    public BloomFilter getBloomFilter(int size, double falsePositiveRate, int nTweak) {
        beginBloomFilterCalculation();
        try {
            BloomFilter filter = keyChainGroup.getBloomFilter(size, falsePositiveRate, nTweak);
            for (Script script : watchedScripts) {
                for (ScriptChunk chunk : script.chunks()) {
                    // Only add long (at least 64 bit) data to the bloom filter.
                    // If any long constants become popular in scripts, we will need logic
                    // here to exclude them.
                    if (!chunk.isOpCode() && (chunk.pushData() != null) && chunk.pushData().length >= MINIMUM_BLOOM_DATA_LENGTH) {
                        filter.insert(chunk.pushData());
                    }
                }
            }
            for (TransactionOutPoint point : bloomOutPoints)
                filter.insert(point);
            return filter;
        } finally {
            endBloomFilterCalculation();
        }
    }

    // Returns true if the output is one that won't be selected by a data element matching in the scriptSig.
    private boolean isTxOutputBloomFilterable(TransactionOutput out) {
        Script script = out.getScriptPubKey();
        boolean isScriptTypeSupported = ScriptPattern.isP2PK(script) || ScriptPattern.isP2SH(script)
                || ScriptPattern.isP2WPKH(script) || ScriptPattern.isP2WSH(script);
        return (isScriptTypeSupported && out.isMine(this)) || watchedScripts.contains(script);
    }

    /**
     * Used by {@link Peer} to decide whether or not to discard this block and any blocks building upon it, in case
     * the Bloom filter used to request them may be exhausted, that is, not have sufficient keys in the deterministic
     * sequence within it to reliably find relevant transactions.
     */
    public boolean checkForFilterExhaustion(FilteredBlock block) {
        keyChainGroupLock.lock();
        try {
            if (!keyChainGroup.supportsDeterministicChains())
                return false;
            int epoch = keyChainGroup.getCombinedKeyLookaheadEpochs();
            for (Transaction tx : block.getAssociatedTransactions().values()) {
                markKeysAsUsed(tx);
            }
            int newEpoch = keyChainGroup.getCombinedKeyLookaheadEpochs();
            checkState(newEpoch >= epoch);
            // If the key lookahead epoch has advanced, there was a call to addKeys and the PeerGroup already has a
            // pending request to recalculate the filter queued up on another thread. The calling Peer should abandon
            // block at this point and await a new filter before restarting the download.
            return newEpoch > epoch;
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    //endregion

    // ***************************************************************************************************************

    //region Extensions to the wallet format.

    /**
     * By providing an object implementing the {@link WalletExtension} interface, you can save and load arbitrary
     * additional data that will be stored with the wallet. Each extension is identified by an ID, so attempting to
     * add the same extension twice (or two different objects that use the same ID) will throw an IllegalStateException.
     */
    public void addExtension(WalletExtension extension) {
        String id = Objects.requireNonNull(extension).getWalletExtensionID();
        lock.lock();
        try {
            if (extensions.containsKey(id))
                throw new IllegalStateException("Cannot add two extensions with the same ID: " + id);
            extensions.put(id, extension);
            saveNow();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically adds extension or returns an existing extension if there is one with the same id already present.
     */
    public WalletExtension addOrGetExistingExtension(WalletExtension extension) {
        String id = Objects.requireNonNull(extension).getWalletExtensionID();
        lock.lock();
        try {
            WalletExtension previousExtension = extensions.get(id);
            if (previousExtension != null)
                return previousExtension;
            extensions.put(id, extension);
            saveNow();
            return extension;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Either adds extension as a new extension or replaces the existing extension if one already exists with the same
     * id. This also triggers wallet auto-saving, so may be useful even when called with the same extension as is
     * already present.
     */
    public void addOrUpdateExtension(WalletExtension extension) {
        String id = Objects.requireNonNull(extension).getWalletExtensionID();
        lock.lock();
        try {
            extensions.put(id, extension);
            saveNow();
        } finally {
            lock.unlock();
        }
    }

    /** Returns a snapshot of all registered extension objects. The extensions themselves are not copied. */
    public Map<String, WalletExtension> getExtensions() {
        lock.lock();
        try {
            return Collections.unmodifiableMap(new HashMap<>(extensions));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Deserialize the wallet extension with the supplied data and then install it, replacing any existing extension
     * that may have existed with the same ID. If an exception is thrown then the extension is removed from the wallet,
     * if already present.
     */
    public void deserializeExtension(WalletExtension extension, byte[] data) throws Exception {
        lock.lock();
        keyChainGroupLock.lock();
        try {
            // This method exists partly to establish a lock ordering of wallet > extension.
            extension.deserializeWalletExtension(this, data);
            extensions.put(extension.getWalletExtensionID(), extension);
        } catch (Throwable throwable) {
            log.error("Error during extension deserialization", throwable);
            extensions.remove(extension.getWalletExtensionID());
            throw throwable;
        } finally {
            keyChainGroupLock.unlock();
            lock.unlock();
        }
    }

    /**
     * @deprecated Applications should use another mechanism to persist application state information
     */
    @Override
    public void setTag(String tag, ByteString value) {
        super.setTag(tag, value);
        saveNow();
    }

    //endregion

    /******************************************************************************************************************/

    private static class FeeCalculation {
        // Selected UTXOs to spend
        public CoinSelection bestCoinSelection;
        // Change output (may be null if no change)
        public TransactionOutput bestChangeOutput;
        // List of output values adjusted downwards when recipients pay fees (may be null if no adjustment needed).
        public List<Coin> updatedOutputValues;
    }

    //region Fee calculation code

    private FeeCalculation calculateFee(SendRequest req, Coin value, boolean needAtLeastReferenceFee, List<TransactionOutput> candidates) throws InsufficientMoneyException {
        checkState(lock.isHeldByCurrentThread());
        FeeCalculation result;
        Coin fee = Coin.ZERO;
        while (true) {
            result = new FeeCalculation();
            Transaction tx = new Transaction();
            addSuppliedInputs(tx, req.tx.getInputs());

            Coin valueNeeded = req.recipientsPayFees ? value : value.add(fee);
            if (req.recipientsPayFees) {
                result.updatedOutputValues = new ArrayList<>();
            }
            for (int i = 0; i < req.tx.getOutputs().size(); i++) {
                TransactionOutput output = TransactionOutput.read(
                        ByteBuffer.wrap(req.tx.getOutput(i).serialize()), tx);
                if (req.recipientsPayFees) {
                    // Subtract fee equally from each selected recipient
                    output = output.withValue(output.getValue().subtract(fee.divide(req.tx.getOutputs().size())));
                    // first receiver pays the remainder not divisible by output count
                    if (i == 0) {
                        // Subtract fee equally from each selected recipient
                        Coin feeRemainder = fee.divideAndRemainder(req.tx.getOutputs().size())[1];
                        output = output.withValue(output.getValue().subtract(feeRemainder));
                    }
                    result.updatedOutputValues.add(output.getValue());
                    Coin nonDustValue = output.getMinNonDustValue();
                    if (output.getValue().isLessThan(nonDustValue)) {
                        throw new CouldNotAdjustDownwards(output.getValue(), nonDustValue);
                    }
                }
                tx.addOutput(output);
            }
            CoinSelector selector = req.coinSelector == null ? coinSelector : req.coinSelector;
            // selector is allowed to modify candidates list.
            CoinSelection selection = selector.select(valueNeeded, new LinkedList<>(candidates));
            result.bestCoinSelection = selection;
            // Can we afford this?
            if (selection.totalValue().compareTo(valueNeeded) < 0) {
                Coin valueMissing = valueNeeded.subtract(selection.totalValue());
                throw new InsufficientMoneyException(valueMissing, selection.totalValue(), value, fee);
            }
            Coin change = selection.totalValue().subtract(valueNeeded);
            if (change.isGreaterThan(Coin.ZERO)) {
                // The value of the inputs is greater than what we want to send. Just like in real life then,
                // we need to take back some coins ... this is called "change". Add another output that sends the change
                // back to us. The address comes either from the request or currentChangeAddress() as a default.
                Address changeAddress = (req.changeAddress != null) ? req.changeAddress : currentChangeAddress();
                TransactionOutput changeOutput = new TransactionOutput(tx, change, changeAddress);
                if (req.recipientsPayFees && changeOutput.isDust()) {
                    // We do not move dust-change to fees, because the sender would end up paying more than requested.
                    // This would be against the purpose of the all-inclusive feature.
                    // So instead we raise the change and deduct from the first recipient.
                    Coin missingToNotBeDust = changeOutput.getMinNonDustValue().subtract(changeOutput.getValue());
                    changeOutput = changeOutput.withValue(changeOutput.getValue().add(missingToNotBeDust));
                    TransactionOutput firstOutput = tx.getOutput(0);
                    firstOutput = firstOutput.withValue(firstOutput.getValue().subtract(missingToNotBeDust));
                    tx.replaceOutput(0, firstOutput);
                    result.updatedOutputValues.set(0, firstOutput.getValue());
                    if (firstOutput.isDust()) {
                        throw new CouldNotAdjustDownwards();
                    }
                }
                if (changeOutput.isDust()) {
                    // Never create dust outputs; if we would, just
                    // add the dust to the fee.
                    // Oscar comment: This seems like a way to make the condition below "if
                    // (!fee.isLessThan(feeNeeded))" to become true.
                    // This is a non-easy to understand way to do that.
                    // Maybe there are other effects I am missing
                    fee = fee.add(changeOutput.getValue());
                } else {
                    tx.addOutput(changeOutput);
                    result.bestChangeOutput = changeOutput;
                }
            }

            for (TransactionOutput selectedOutput : selection.outputs()) {
                TransactionInput input = tx.addInput(selectedOutput);
                // If the scriptBytes don't default to none, our size calculations will be thrown off.
                checkState(input.getScriptBytes().length == 0);
                checkState(!input.hasWitness());
            }

            Coin feeNeeded = estimateFees(tx, selection, req.feePerKb, needAtLeastReferenceFee);

            if (!fee.isLessThan(feeNeeded)) {
                // Done, enough fee included.
                break;
            }

            // Include more fee and try again.
            fee = feeNeeded;
        }
        return result;

    }

    private void addSuppliedInputs(Transaction tx, List<TransactionInput> originalInputs) {
        for (TransactionInput input : originalInputs)
            tx.addInput(TransactionInput.read(ByteBuffer.wrap(input.serialize()), tx));
    }

    private Coin estimateFees(Transaction tx, CoinSelection coinSelection, Coin requestedFeePerKb, boolean ensureMinRequiredFee) {
        Coin feePerKb = (ensureMinRequiredFee && requestedFeePerKb.isLessThan(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE))
                ? Transaction.REFERENCE_DEFAULT_MIN_TX_FEE
                : requestedFeePerKb;
        int vSize = tx.getVsize() + estimateVirtualBytesForSigning(coinSelection.outputs());
        return feePerKb.multiply(vSize).divide(1000);
    }

    private int estimateVirtualBytesForSigning(List<TransactionOutput> outputs) {
        return outputs.stream()
                .map(TransactionOutput::getScriptPubKey)
                .mapToInt(this::estimateVirtualBytesForSigning)
                .sum();
    }

    private int estimateVirtualBytesForSigning(Script script) {
        try {
            if (ScriptPattern.isP2PKH(script)) {
                ECKey key = findKeyFromPubKeyHash(ScriptPattern.extractHashFromP2PKH(script), ScriptType.P2PKH);
                Objects.requireNonNull(key, "Coin selection includes unspendable outputs");
                return script.getNumberOfBytesRequiredToSpend(key, null);
            } else if (ScriptPattern.isP2WPKH(script)) {
                ECKey key = findKeyFromPubKeyHash(ScriptPattern.extractHashFromP2WH(script), ScriptType.P2WPKH);
                Objects.requireNonNull(key, "Coin selection includes unspendable outputs");
                return IntMath.divide(script.getNumberOfBytesRequiredToSpend(key, null), 4,
                        RoundingMode.CEILING); // round up
            } else if (ScriptPattern.isP2SH(script)) {
                Script redeemScript = findRedeemDataFromScriptHash(ScriptPattern.extractHashFromP2SH(script)).redeemScript;
                Objects.requireNonNull(redeemScript, "Coin selection includes unspendable outputs");
                return script.getNumberOfBytesRequiredToSpend(null, redeemScript);
            } else {
                return script.getNumberOfBytesRequiredToSpend(null, null);
            }
        } catch (ScriptException e) {
            // If this happens it means an output script in a wallet tx could not be understood. That should never
            // happen, if it does it means the wallet has got into an inconsistent state.
            throw new IllegalStateException(e);
        }
    }

    //endregion

    // ***************************************************************************************************************

    //region Wallet maintenance transactions

    // Wallet maintenance transactions. These transactions may not be directly connected to a payment the user is
    // making. They may be instead key rotation transactions for when old keys are suspected to be compromised,
    // de/re-fragmentation transactions for when our output sizes are inappropriate or suboptimal, privacy transactions
    // and so on. Because these transactions may require user intervention in some way (e.g. entering their password)
    // the wallet application is expected to poll the Wallet class to get SendRequests. Ideally security systems like
    // hardware wallets or risk analysis providers are programmed to auto-approve transactions that send from our own
    // keys back to our own keys.

    /**
     * <p>Specifies that the given {@link TransactionBroadcaster}, typically a {@link PeerGroup}, should be used for
     * sending transactions to the Bitcoin network by default. Some sendCoins methods let you specify a broadcaster
     * explicitly, in that case, they don't use this broadcaster. If null is specified then the wallet won't attempt
     * to broadcast transactions itself.</p>
     *
     * <p>You don't normally need to call this. A {@link PeerGroup} will automatically set itself as the wallets
     * broadcaster when you use {@link PeerGroup#addWallet(Wallet)}. A wallet can use the broadcaster when you ask
     * it to send money, but in future also at other times to implement various features that may require asynchronous
     * re-organisation of the wallet contents on the block chain. For instance, in future the wallet may choose to
     * optimise itself to reduce fees or improve privacy.</p>
     */
    public void setTransactionBroadcaster(@Nullable org.bitcoinj.core.TransactionBroadcaster broadcaster) {
        Transaction[] toBroadcast = {};
        lock.lock();
        try {
            if (vTransactionBroadcaster == broadcaster)
                return;
            vTransactionBroadcaster = broadcaster;
            if (broadcaster == null)
                return;
            toBroadcast = pending.values().toArray(toBroadcast);
        } finally {
            lock.unlock();
        }
        // Now use it to upload any pending transactions we have that are marked as not being seen by any peers yet.
        // Don't hold the wallet lock whilst doing this, so if the broadcaster accesses the wallet at some point there
        // is no inversion.
        for (Transaction tx : toBroadcast) {
            ConfidenceType confidenceType = getConfidence(tx).getConfidenceType();
            checkState(confidenceType == ConfidenceType.PENDING || confidenceType == ConfidenceType.IN_CONFLICT, () ->
                    "Tx " + tx.getTxId() + ": expected PENDING or IN_CONFLICT, was " + confidenceType);
            // Re-broadcast even if it's marked as already seen for two reasons
            // 1) Old wallets may have transactions marked as broadcast by 1 peer when in reality the network
            //    never saw it, due to bugs.
            // 2) It can't really hurt.
            log.info("New broadcaster so uploading waiting tx {}", tx.getTxId());
            broadcaster.broadcastTransaction(tx);
        }
    }

    /**
     * <p>
     * When a key rotation time is set, any money controlled by keys created before the given timestamp T will be
     * respent to any key that was created after T. This can be used to recover from a situation where a set of keys is
     * believed to be compromised. The rotation time is persisted to the wallet. You can stop key rotation by calling
     * this method again with {@code null} as the argument.
     * </p>
     * 
     * <p>
     * Once set up, calling {@link #doMaintenance(AesKey, boolean)} will create and possibly send rotation
     * transactions: but it won't be done automatically (because you might have to ask for the users password). This may
     * need to be repeated regularly in case new coins keep coming in on rotating addresses/keys.
     * </p>
     *
     * <p>
     * The given time cannot be in the future.
     * </p>
     * @param keyRotationTime rotate any keys created before this time, or null for no rotation
     */
    public void setKeyRotationTime(@Nullable Instant keyRotationTime) {
        checkArgument(keyRotationTime == null || keyRotationTime.compareTo(TimeUtils.currentTime()) <= 0, () ->
                "given time cannot be in the future: " + TimeUtils.dateTimeFormat(keyRotationTime));
        vKeyRotationTime = keyRotationTime;
        saveNow();
    }

    /**
     * Returns the key rotation time, or empty if unconfigured. See {@link #setKeyRotationTime(Instant)} for a description
     * of the field.
     */
    public Optional<Instant> keyRotationTime() {
        return Optional.ofNullable(vKeyRotationTime);
    }

    /** Returns whether the keys creation time is before the key rotation time, if one was set. */
    public boolean isKeyRotating(ECKey key) {
        Instant keyRotationTime = vKeyRotationTime;
        return keyRotationTime != null && key.getCreationTime().orElse(Instant.EPOCH).isBefore(keyRotationTime);
    }

    /**
     * A wallet app should call this from time to time in order to let the wallet craft and send transactions needed
     * to re-organise coins internally. A good time to call this would be after receiving coins for an unencrypted
     * wallet, or after sending money for an encrypted wallet. If you have an encrypted wallet and just want to know
     * if some maintenance needs doing, call this method with andSend set to false and look at the returned list of
     * transactions. Maintenance might also include internal changes that involve some processing or work but
     * which don't require making transactions - these will happen automatically unless the password is required
     * in which case an exception will be thrown.
     * @param aesKey the users password, if any.
     * @param signAndSend if true, send the transactions via the tx broadcaster and return them, if false just return them.
     *
     * @return A list of transactions that the wallet just made/will make for internal maintenance. Might be empty.
     * @throws org.bitcoinj.wallet.DeterministicUpgradeRequiresPassword if key rotation requires the users password.
     */
    public CompletableFuture<List<Transaction>> doMaintenance(@Nullable AesKey aesKey, boolean signAndSend)
            throws DeterministicUpgradeRequiresPassword {
        return doMaintenance(KeyChainGroupStructure.BIP32, aesKey, signAndSend);
    }

    /**
     * A wallet app should call this from time to time in order to let the wallet craft and send transactions needed
     * to re-organise coins internally. A good time to call this would be after receiving coins for an unencrypted
     * wallet, or after sending money for an encrypted wallet. If you have an encrypted wallet and just want to know
     * if some maintenance needs doing, call this method with andSend set to false and look at the returned list of
     * transactions. Maintenance might also include internal changes that involve some processing or work but
     * which don't require making transactions - these will happen automatically unless the password is required
     * in which case an exception will be thrown.
     * @param structure to derive the account path from if a new seed needs to be created
     * @param aesKey the users password, if any.
     * @param signAndSend if true, send the transactions via the tx broadcaster and return them, if false just return them.
     *
     * @return A list of transactions that the wallet just made/will make for internal maintenance. Might be empty.
     * @throws org.bitcoinj.wallet.DeterministicUpgradeRequiresPassword if key rotation requires the users password.
     */
    public CompletableFuture<List<Transaction>> doMaintenance(KeyChainGroupStructure structure,
            @Nullable AesKey aesKey, boolean signAndSend) throws DeterministicUpgradeRequiresPassword {
        List<Transaction> txns;
        lock.lock();
        keyChainGroupLock.lock();
        try {
            txns = maybeRotateKeys(structure, aesKey, signAndSend);
            if (!signAndSend)
                return CompletableFuture.completedFuture(txns);
        } finally {
            keyChainGroupLock.unlock();
            lock.unlock();
        }
        checkState(!lock.isHeldByCurrentThread());
        List<CompletableFuture<Transaction>> futures = new ArrayList<>(txns.size());
        TransactionBroadcaster broadcaster = vTransactionBroadcaster;
        for (Transaction tx : txns) {
            try {
                final CompletableFuture<Transaction> future = broadcaster.broadcastTransaction(tx)
                        .awaitRelayed()
                        .thenApply(TransactionBroadcast::transaction);
                futures.add(future);
                future.whenComplete((transaction, throwable) -> {
                    if (transaction != null) {
                        log.info("Successfully broadcast key rotation tx: {}", transaction);
                    } else {
                        log.error("Failed to broadcast key rotation tx", throwable);
                    }
                });
            } catch (Exception e) {
                log.error("Failed to broadcast rekey tx", e);
            }
        }
        return FutureUtils.allAsList(futures);
    }

    // Checks to see if any coins are controlled by rotating keys and if so, spends them.
    @GuardedBy("keyChainGroupLock")
    private List<Transaction> maybeRotateKeys(KeyChainGroupStructure structure, @Nullable AesKey aesKey,
            boolean sign) throws DeterministicUpgradeRequiresPassword {
        checkState(lock.isHeldByCurrentThread());
        checkState(keyChainGroupLock.isHeldByCurrentThread());
        List<Transaction> results = new LinkedList<>();
        // TODO: Handle chain replays here.
        Instant keyRotationTime = vKeyRotationTime;
        if (keyRotationTime == null) return results;  // Nothing to do.

        // We might have to create a new HD hierarchy if the previous ones are now rotating.
        boolean allChainsRotating = true;
        ScriptType preferredScriptType = ScriptType.P2PKH;
        if (keyChainGroup.supportsDeterministicChains()) {
            for (DeterministicKeyChain chain : keyChainGroup.getDeterministicKeyChains()) {
                if (chain.earliestKeyCreationTime().compareTo(keyRotationTime) >= 0)
                    allChainsRotating = false;
                else
                    preferredScriptType = chain.getOutputScriptType();
            }
        }
        if (allChainsRotating) {
            try {
                if (keyChainGroup.getImportedKeys().isEmpty()) {
                    log.info(
                            "All deterministic chains are currently rotating and we have no random keys, creating fresh {} chain: backup required after this.",
                            preferredScriptType);
                    KeyChainGroup newChains = KeyChainGroup.builder(network, structure).fromRandom(preferredScriptType)
                            .build();
                    if (keyChainGroup.isEncrypted()) {
                        if (aesKey == null)
                            throw new DeterministicUpgradeRequiresPassword();
                        KeyCrypter keyCrypter = keyChainGroup.getKeyCrypter();
                        keyChainGroup.decrypt(aesKey);
                        keyChainGroup.mergeActiveKeyChains(newChains, keyRotationTime);
                        keyChainGroup.encrypt(keyCrypter, aesKey);
                    } else {
                        keyChainGroup.mergeActiveKeyChains(newChains, keyRotationTime);
                    }
                } else {
                    log.info(
                            "All deterministic chains are currently rotating, creating a new {} one from the next oldest non-rotating key material...",
                            preferredScriptType);
                    keyChainGroup.upgradeToDeterministic(preferredScriptType, structure, keyRotationTime, aesKey);
                    log.info("...upgraded to HD again, based on next best oldest key.");
                }
            } catch (AllRandomKeysRotating rotating) {
                log.info(
                        "No non-rotating random keys available, generating entirely new {} tree: backup required after this.",
                        preferredScriptType);
                KeyChainGroup newChains = KeyChainGroup.builder(network, structure).fromRandom(preferredScriptType)
                        .build();
                if (keyChainGroup.isEncrypted()) {
                    if (aesKey == null)
                        throw new DeterministicUpgradeRequiresPassword();
                    KeyCrypter keyCrypter = keyChainGroup.getKeyCrypter();
                    keyChainGroup.decrypt(aesKey);
                    keyChainGroup.mergeActiveKeyChains(newChains, keyRotationTime);
                    keyChainGroup.encrypt(keyCrypter, aesKey);
                } else {
                    keyChainGroup.mergeActiveKeyChains(newChains, keyRotationTime);
                }
            }
            saveNow();
        }

        // Because transactions are size limited, we might not be able to re-key the entire wallet in one go. So
        // loop around here until we no longer produce transactions with the max number of inputs. That means we're
        // fully done, at least for now (we may still get more transactions later and this method will be reinvoked).
        Transaction tx;
        do {
            tx = rekeyOneBatch(keyRotationTime, aesKey, results, sign);
            if (tx != null) results.add(tx);
        } while (tx != null && tx.getInputs().size() == KeyTimeCoinSelector.MAX_SIMULTANEOUS_INPUTS);
        return results;
    }

    @Nullable
    private Transaction rekeyOneBatch(Instant time, @Nullable AesKey aesKey, List<Transaction> others, boolean sign) {
        lock.lock();
        try {
            // Build the transaction using some custom logic for our special needs. Last parameter to
            // KeyTimeCoinSelector is whether to ignore pending transactions or not.
            //
            // We ignore pending outputs because trying to rotate these is basically racing an attacker, and
            // we're quite likely to lose and create stuck double spends. Also, some users who have 0.9 wallets
            // have already got stuck double spends in their wallet due to the Bloom-filtering block reordering
            // bug that was fixed in 0.10, thus, making a re-key transaction depend on those would cause it to
            // never confirm at all.
            List<TransactionOutPoint> excluded = others.stream()
                    .flatMap(tx -> tx.getInputs().stream())
                    .map(TransactionInput::getOutpoint)
                    .collect(StreamUtils.toUnmodifiableList());
            CoinSelector keyTimeSelector = new KeyTimeCoinSelector(this, time, true);
            FilteringCoinSelector selector = new FilteringCoinSelector(keyTimeSelector, excluded);
            // TODO: Make this use the standard SendRequest.
            CoinSelection toMove = selector.select(Coin.ZERO, calculateAllSpendCandidates());
            if (toMove.totalValue().equals(Coin.ZERO)) return null;  // Nothing to do.
            Transaction rekeyTx = new Transaction();
            for (TransactionOutput output : toMove.outputs()) {
                rekeyTx.addInput(output);
            }
            // When not signing, don't waste addresses.
            rekeyTx.addOutput(toMove.totalValue(), sign ? freshReceiveAddress() : currentReceiveAddress());
            if (!adjustOutputDownwardsForFee(rekeyTx, toMove, Transaction.DEFAULT_TX_FEE, true)) {
                log.error("Failed to adjust rekey tx for fees.");
                return null;
            }
            getConfidence(rekeyTx).setSource(TransactionConfidence.Source.SELF);
            rekeyTx.setPurpose(Transaction.Purpose.KEY_ROTATION);
            SendRequest req = SendRequest.forTx(rekeyTx);
            req.aesKey = aesKey;
            if (sign)
                signTransaction(req);
            // KeyTimeCoinSelector should never select enough inputs to push us oversize.
            checkState(rekeyTx.messageSize() < Transaction.MAX_STANDARD_TX_SIZE);
            return rekeyTx;
        } catch (VerificationException e) {
            throw new RuntimeException(e);  // Cannot happen.
        } finally {
            lock.unlock();
        }
    }
    //endregion
}
