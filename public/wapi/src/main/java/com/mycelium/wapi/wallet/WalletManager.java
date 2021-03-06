/*
 * Copyright 2013, 2014 Megion Research & Development GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mycelium.wapi.wallet;

import com.google.common.base.*;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mrd.bitlib.crypto.Bip39;
import com.mrd.bitlib.crypto.HdKeyNode;
import com.mrd.bitlib.crypto.InMemoryPrivateKey;
import com.mrd.bitlib.crypto.PublicKey;
import com.mrd.bitlib.model.Address;
import com.mrd.bitlib.model.NetworkParameters;
import com.mrd.bitlib.util.HexUtils;
import com.mycelium.wapi.api.Wapi;
import com.mycelium.wapi.api.WapiLogger;
import com.mycelium.wapi.wallet.KeyCipher.InvalidKeyCipher;
import com.mycelium.wapi.wallet.bip44.Bip44Account;
import com.mycelium.wapi.wallet.bip44.Bip44AccountContext;
import com.mycelium.wapi.wallet.bip44.Bip44AccountKeyManager;
import com.mycelium.wapi.wallet.single.PublicPrivateKeyStore;
import com.mycelium.wapi.wallet.single.SingleAddressAccount;
import com.mycelium.wapi.wallet.single.SingleAddressAccountContext;

import java.util.*;

/**
 * Allows you to manage a wallet that contains multiple HD accounts and
 * 'classic' single address accounts.
 */
//TODO we might optimize away full TX history for cold storage spending

public class WalletManager {

   private static final byte[] MASTER_SEED_ID = HexUtils.toBytes("D64CA2B680D8C8909A367F28EB47F990");

   /**
    * Implement this interface to get a callback when the wallet manager changes
    * state or when some event occurs
    */
   public interface Observer {

      /**
       * Callback which occurs when the state of a wallet manager changes while
       * the wallet is synchronizing
       *
       * @param wallet the wallet manager instance
       * @param state  the new state of the wallet manager
       */
      void onWalletStateChanged(WalletManager wallet, State state);

      /**
       * Callback telling that an event occurred while synchronizing
       *
       * @param wallet    the wallet manager
       * @param accountId the ID of the account causing the event
       * @param events    the event that occurred
       */
      void onAccountEvent(WalletManager wallet, UUID accountId, Event events);
   }

   public enum State {
      /**
       * The wallet manager is synchronizing
       */
      SYNCHRONIZING,
      /**
       * The wallet manager is ready
       */
      READY
   }

   public enum Event {
      /**
       * There is currently no connection to the block chain. This is probably
       * due to network issues, or the Mycelium servers are down (unlikely).
       */
      SERVER_CONNECTION_ERROR,
      /**
       * The wallet broadcasted a transaction which was accepted by the network
       */
      BROADCASTED_TRANSACTION_ACCEPTED,
      /**
       * The wallet broadcasted a transaction which was rejected by the network.
       * This is an rare event which could happen if you double spend yourself,
       * or you spent an unconfirmed change which was subject to a malleability
       * attack
       */
      BROADCASTED_TRANSACTION_DENIED,
      /**
       * The balance of the account changed
       */
      BALANCE_CHANGED,
      /**
       * The transaction history of the account changed
       */
      TRANSACTION_HISTORY_CHANGED,
      /**
       * The receiving address of an account has been updated
       */
      RECEIVING_ADDRESS_CHANGED
   }

   private final SecureKeyValueStore _secureKeyValueStore;
   private WalletManagerBacking _backing;
   private final Map<UUID, AbstractAccount> _allAccounts;
   private final List<Bip44Account> _bip44Accounts;
   private final Collection<Observer> _observers;
   private State _state;
   private Thread _synchronizationThread;
   private AccountEventManager _accountEventManager;
   private NetworkParameters _network;
   private Wapi _wapi;
   private WapiLogger _logger;
   private boolean _synchronizeTransactionHistory;

   /**
    * Create a new wallet manager instance
    *
    * @param backing the backing to use for storing everything related to wallet accounts
    * @param network the network used
    * @param wapi    the Wapi instance to use
    */
   public WalletManager(SecureKeyValueStore secureKeyValueStore, WalletManagerBacking backing,
                        NetworkParameters network, Wapi wapi) {
      _secureKeyValueStore = secureKeyValueStore;
      _backing = backing;
      _network = network;
      _wapi = wapi;
      _logger = _wapi.getLogger();
      _allAccounts = new HashMap<UUID, AbstractAccount>();
      _bip44Accounts = new ArrayList<Bip44Account>();
      _state = State.READY;
      _accountEventManager = new AccountEventManager();
      _observers = new LinkedList<Observer>();
      _synchronizeTransactionHistory = true;
      loadAccounts();
   }

   /**
    * Get the current state
    *
    * @return the current state
    */
   public State getState() {
      return _state;
   }

   /**
    * Add an observer that gets callbacks when the wallet manager state changes
    * or account events occur.
    *
    * @param observer the observer to add
    */
   public void addObserver(Observer observer) {
      synchronized (_observers) {
         _observers.add(observer);
      }
   }

   /**
    * Remove an observer
    *
    * @param observer the observer to remove
    */
   public void removeObserver(Observer observer) {
      synchronized (_observers) {
         _observers.remove(observer);
      }
   }

   /**
    * Create a new read-only account using a single address
    *
    * @param address the address to use
    * @return the ID of the new account
    */
   public UUID createSingleAddressAccount(Address address) {
      UUID id = SingleAddressAccount.calculateId(address);
      synchronized (_allAccounts) {
         if (_allAccounts.containsKey(id)) {
            return id;
         }
         _backing.beginTransaction();
         try {
            SingleAddressAccountContext context = new SingleAddressAccountContext(id, address, false, 0);
            _backing.createSingleAddressAccountContext(context);
            SingleAddressAccountBacking accountBacking = _backing.getSingleAddressAccountBacking(context.getId());
            Preconditions.checkNotNull(accountBacking);
            PublicPrivateKeyStore store = new PublicPrivateKeyStore(_secureKeyValueStore);
            SingleAddressAccount account = new SingleAddressAccount(context, store, _network, accountBacking, _wapi);
            context.persist(accountBacking);
            _backing.setTransactionSuccessful();
            addAccount(account);
         } finally {
            _backing.endTransaction();
         }
      }
      return id;
   }

   /**
    * Create a new account using a single private key and address
    *
    * @param privateKey key the private key to use
    * @param cipher     the cipher used to encrypt the private key. Must be the same
    *                   cipher as the one used by the secure storage instance
    * @return the ID of the new account
    * @throws InvalidKeyCipher
    */
   public UUID createSingleAddressAccount(InMemoryPrivateKey privateKey, KeyCipher cipher) throws InvalidKeyCipher {
      PublicKey publicKey = privateKey.getPublicKey();
      Address address = publicKey.toAddress(_network);
      PublicPrivateKeyStore store = new PublicPrivateKeyStore(_secureKeyValueStore);
      store.setPrivateKey(address, privateKey, cipher);
      return createSingleAddressAccount(address);
   }

   /**
    * Delete an account that uses a single address
    * <p/>
    * This method cannot be used for deleting HD accounts.
    *
    * @param id the ID of the account to delete.
    */
   public void deleteSingleAddressAccount(UUID id, KeyCipher cipher) throws InvalidKeyCipher {
      synchronized (_allAccounts) {
         AbstractAccount account = _allAccounts.get(id);
         if (!(account instanceof SingleAddressAccount)) {
            return;
         }
         SingleAddressAccount singleAddressAccount = (SingleAddressAccount) account;
         singleAddressAccount.forgetPrivateKey(cipher);
         account.setEventHandler(null);
         _backing.deleteSingleAddressAccountContext(id);
         _allAccounts.remove(id);
      }
   }

   /**
    * Call this method to disable transaction history synchronization for single address accounts.
    * <p/>
    * This is useful if the wallet manager is used for cold storage spending where the transaction history is
    * uninteresting. Disabling transaction history synchronization makes synchronization faster especially if the
    * address has been used a lot.
    */
   public void disableTransactionHistorySynchronization() {
      _synchronizeTransactionHistory = false;
   }

   /**
    * Get the IDs of the accounts managed by the wallet manager
    *
    * @return the IDs of the accounts managed by the wallet manager
    */
   public List<UUID> getAccountIds() {
      List<UUID> list = new ArrayList<UUID>(_allAccounts.size());
      for (AbstractAccount account : _allAccounts.values()) {
         list.add(account.getId());
      }
      return list;
   }

   /**
    * Get the active accounts managed by the wallet manager, excluding on-the-fly-accounts
    *
    * @return the active accounts managed by the wallet manager
    */
   public List<WalletAccount> getActiveAccounts() {
      return filterAndConvert(Predicates.not(IS_ARCHIVE));
   }

   /**
    * Get archived accounts managed by the wallet manager
    *
    * @return the archived accounts managed by the wallet manager
    */
   public List<WalletAccount> getArchivedAccounts() {
      return filterAndConvert(IS_ARCHIVE);
   }

   /**
    * Get accounts that can spend and are active
    *
    * @return the list of accounts
    */
   public List<WalletAccount> getSpendingAccounts() {
      return filterAndConvert(ACTIVE_CAN_SPEND);
   }

   /**
    * Get accounts that can spend and have a positive balance
    *
    * @return the list of accounts
    */
   public List<WalletAccount> getSpendingAccountsWithBalance() {
      return filterAndConvert(Predicates.and(ACTIVE_CAN_SPEND, HAS_BALANCE));
   }

   private List<WalletAccount> filterAndConvert(Predicate<Map.Entry<UUID, AbstractAccount>> filter) {
      Set<UUID> uuids = Maps.filterEntries(_allAccounts, filter).keySet();
      return Lists.transform(Lists.newArrayList(uuids), key2Account);
   }

   /**
    * Check whether the wallet manager has a particular account
    *
    * @param id the account to look for
    * @return true iff the wallet manager has an account with the specified ID
    */
   public boolean hasAccount(UUID id) {
      return _allAccounts.containsKey(id);
   }

   /**
    * Get a wallet account
    *
    * @param id the ID of the account to get
    * @return a wallet account
    */
   public WalletAccount getAccount(UUID id) {
      return Preconditions.checkNotNull(_allAccounts.get(id));
   }

   /**
    * Make the wallet manager synchronize all its active accounts.
    * <p/>
    * Synchronization occurs in the background. To get feedback register an
    * observer.
    */
   public void startSynchronization() {
      if (_synchronizationThread != null) {
         // Already running
         return;
      }
      // Launch synchronizer thread
      Synchronizer synchronizer = new Synchronizer();
      _synchronizationThread = new Thread(synchronizer);
      _synchronizationThread.setDaemon(true);
      _synchronizationThread.setName(synchronizer.getClass().getSimpleName());
      _synchronizationThread.start();
   }

   @Override
   public String toString() {
      StringBuilder sb = new StringBuilder();
      int Bip44Accounts = 0;
      int simpleAccounts = 0;
      for (UUID id : getAccountIds()) {
         if (_allAccounts.get(id) instanceof Bip44Account) {
            Bip44Accounts++;
         } else if (_allAccounts.get(id) instanceof SingleAddressAccount) {
            simpleAccounts++;
         }
      }
      sb.append("Accounts: ").append(_allAccounts.size()).append(" Active: ").append(getActiveAccounts().size())
            .append(" HD: ").append(Bip44Accounts).append(" Simple:").append(simpleAccounts);
      return sb.toString();
   }

   /**
    * Determine whether this address is managed by an account of the wallet
    *
    * @param address the address to query for
    * @return if any account in the wallet manager has the address
    */
   public boolean isMyAddress(Address address) {
      return getAccountByAddress(address).isPresent();
   }


   /**
    * Get the account associated with an address if any
    *
    * @param address the address to query for
    * @return the account UUID if found.
    */
   public synchronized Optional<UUID> getAccountByAddress(Address address) {
      for (AbstractAccount account : _allAccounts.values()) {
         if (account.isMine(address)) {
            return Optional.of(account.getId());
         }
      }
      return Optional.absent();
   }

   /**
    * Determine whether any account in the wallet manager has the private key for the specified address
    *
    * @param address the address to query for
    * @return true if any account in the wallet manager has the private key for the specified address
    */
   public synchronized boolean hasPrivateKeyForAddress(Address address) {
      for (AbstractAccount account : _allAccounts.values()) {
         if (account.isMine(address) && account.canSpend()) {
            return true;
         }
      }
      return false;
   }

   private void setStateAndNotify(State state) {
      _state = state;
      synchronized (_observers) {
         for (Observer o : _observers) {
            o.onWalletStateChanged(this, _state);
         }
      }
   }

   private void loadAccounts() {
      if (hasBip32MasterSeed()) {
         loadBip44Accounts();
      }
      // Load all single address accounts
      loadSingleAddressAccounts();
   }

   private void loadBip44Accounts() {
      _logger.logInfo("Loading BIP44 accounts");
      List<Bip44AccountContext> contexts = _backing.loadBip44AccountContexts();
      for (Bip44AccountContext context : contexts) {
         Bip44AccountKeyManager keyManager = new Bip44AccountKeyManager(context.getAccountIndex(), _network, _secureKeyValueStore);
         Bip44AccountBacking accountBacking = _backing.getBip44AccountBacking(context.getId());
         Preconditions.checkNotNull(accountBacking);
         Bip44Account account = new Bip44Account(context, keyManager, _network, accountBacking, _wapi);
         addAccount(account);
         _bip44Accounts.add(account);
      }
   }

   private void loadSingleAddressAccounts() {
      _logger.logInfo("Loading single address accounts");
      List<SingleAddressAccountContext> contexts = _backing.loadSingleAddressAccountContexts();
      for (SingleAddressAccountContext context : contexts) {
         PublicPrivateKeyStore store = new PublicPrivateKeyStore(_secureKeyValueStore);
         SingleAddressAccountBacking accountBacking = _backing.getSingleAddressAccountBacking(context.getId());
         Preconditions.checkNotNull(accountBacking);
         SingleAddressAccount account = new SingleAddressAccount(context, store, _network, accountBacking, _wapi);
         addAccount(account);
      }
   }

   private void addAccount(AbstractAccount account) {
      synchronized (_allAccounts) {
         account.setEventHandler(_accountEventManager);
         _allAccounts.put(account.getId(), account);
         _logger.logInfo("Account Added: " + account.getId());
      }
   }


   private class Synchronizer implements Runnable {

      @Override
      public void run() {
         try {
            setStateAndNotify(State.SYNCHRONIZING);
            synchronized (_allAccounts) {
               // If we have any lingering outgoing transactions broadcast them
               // now
               if (!broadcastOutgoingTransactions()) {
                  return;
               }

               // Synchronize every account with the blockchain
               if (!synchronize()) {
                  return;
               }
            }
         } finally {
            _synchronizationThread = null;
            setStateAndNotify(State.READY);
         }
      }

      private boolean broadcastOutgoingTransactions() {
         for (AbstractAccount account : _allAccounts.values()) {
            if (account.isArchived()) {
               continue;
            }
            if (!account.broadcastOutgoingTransactions()) {
               // We failed to broadcast due to API error, we will have to try
               // again later
               return false;
            }
         }
         return true;
      }

      private boolean synchronize() {
         for (AbstractAccount account : _allAccounts.values()) {
            if (account.isArchived()) {
               continue;
            }
            if (!account.synchronize(_synchronizeTransactionHistory)) {
               // We failed to broadcast due to API error, we will have to try
               // again later
               return false;
            }
         }
         return true;
      }

   }

   private class AccountEventManager implements AbstractAccount.EventHandler {
      @Override
      public void onEvent(UUID accountId, Event event) {
         synchronized (_observers) {
            for (Observer o : _observers) {
               o.onAccountEvent(WalletManager.this, accountId, event);
            }
         }
      }
   }

   /**
    * Determine whether the wallet manager has a master seed configured
    *
    * @return true iff a master seed has been configured for this wallet manager
    */
   public boolean hasBip32MasterSeed() {
      return _secureKeyValueStore.hasCiphertextValue(MASTER_SEED_ID);
   }

   /**
    * Get the master seed in plain text
    *
    * @param cipher the cipher used to decrypt the master seed
    * @return the master seed in plain text
    * @throws InvalidKeyCipher if the cipher is invalid
    */
   public Bip39.MasterSeed getMasterSeed(KeyCipher cipher) throws InvalidKeyCipher {

      byte[] binaryMasterSeed = _secureKeyValueStore.getEncryptedValue(MASTER_SEED_ID, cipher);
      Optional<Bip39.MasterSeed> masterSeed = Bip39.MasterSeed.fromBytes(binaryMasterSeed, false);
      if (!masterSeed.isPresent()) {
         throw new RuntimeException();
      }
      return masterSeed.get();
   }

   /**
    * Configure the BIP32 master seed of this wallet manager
    *
    * @param masterSeed the master seed to use.
    * @param cipher     the cipher used to encrypt the master seed. Must be the same
    *                   cipher as the one used by the secure storage instance
    * @throws InvalidKeyCipher if the cipher is invalid
    */
   public void configureBip32MasterSeed(Bip39.MasterSeed masterSeed, KeyCipher cipher) throws InvalidKeyCipher {
      if (hasBip32MasterSeed()) {
         throw new RuntimeException("HD key store already loaded");
      }
      _secureKeyValueStore.encryptAndStoreValue(MASTER_SEED_ID, masterSeed.toBytes(false), cipher);
   }

   private static final Predicate<Map.Entry<UUID, AbstractAccount>> IS_ARCHIVE = new Predicate<Map.Entry<UUID, AbstractAccount>>() {
      @Override
      public boolean apply(Map.Entry<UUID, AbstractAccount> input) {
         return input.getValue().isArchived();
      }
   };

   private static final Predicate<Map.Entry<UUID, AbstractAccount>> ACTIVE_CAN_SPEND = new Predicate<Map.Entry<UUID, AbstractAccount>>() {
      @Override
      public boolean apply(Map.Entry<UUID, AbstractAccount> input) {
         return input.getValue().isActive() && input.getValue().canSpend();
      }
   };

   private static final Predicate<Map.Entry<UUID, AbstractAccount>> HAS_BALANCE = new Predicate<Map.Entry<UUID, AbstractAccount>>() {
      @Override
      public boolean apply(Map.Entry<UUID, AbstractAccount> input) {
         return input.getValue().getBalance().getSpendableBalance() > 0;
      }
   };

   private final Function<UUID, WalletAccount> key2Account = new Function<UUID, WalletAccount>() {
      @Override
      public WalletAccount apply(UUID input) {
         return getAccount(input);
      }
   };

   public boolean canCreateAdditionalBip44Account() {
      if (!hasBip32MasterSeed()) {
         // No master seed
         return false;
      }
      if (getNextBip44Index() == 0) {
         // First account not created
         return true;
      }
      // We can add an additional account if the last account had activity
      Bip44Account last = _bip44Accounts.get(_bip44Accounts.size() - 1);
      return last.hasHadActivity();
   }

   private int getNextBip44Index() {
      return _bip44Accounts.size();
   }

   public UUID createAdditionalBip44Account(KeyCipher cipher) throws InvalidKeyCipher {
      if (!canCreateAdditionalBip44Account()) {
         throw new RuntimeException("Unable to create additional HD account");
      }

      // Get the master seed
      Bip39.MasterSeed mastrSeed = getMasterSeed(cipher);

      // Generate the root private key
      HdKeyNode root = HdKeyNode.fromSeed(mastrSeed.getBip32Seed());

      synchronized (_allAccounts) {
         // Determine the next BIP44 account index
         int accountIndex = getNextBip44Index();

         _backing.beginTransaction();
         try {
            // Create the base keys for the account
            Bip44AccountKeyManager keyManager = Bip44AccountKeyManager.createNew(root, _network, accountIndex, _secureKeyValueStore, cipher);

            // Generate the context for the account
            Bip44AccountContext context = new Bip44AccountContext(keyManager.getAccountId(), accountIndex, false);
            _backing.createBip44AccountContext(context);

            // Get the backing for the new account
            Bip44AccountBacking accountBacking = _backing.getBip44AccountBacking(context.getId());
            Preconditions.checkNotNull(accountBacking);


            // Create actual account
            Bip44Account account = new Bip44Account(context, keyManager, _network, accountBacking, _wapi);

            // Finally persist context and add account
            context.persist(accountBacking);
            _backing.setTransactionSuccessful();
            addAccount(account);
            _bip44Accounts.add(account);
            return account.getId();
         } finally {
            _backing.endTransaction();
         }
      }
   }


}
