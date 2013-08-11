/*
 * Copyright 2013 Megion Research and Development GmbH
 *
 * Licensed under the Microsoft Reference Source License (MS-RSL)
 *
 * This license governs use of the accompanying software. If you use the software, you accept this license.
 * If you do not accept the license, do not use the software.
 *
 * 1. Definitions
 * The terms "reproduce," "reproduction," and "distribution" have the same meaning here as under U.S. copyright law.
 * "You" means the licensee of the software.
 * "Your company" means the company you worked for when you downloaded the software.
 * "Reference use" means use of the software within your company as a reference, in read only form, for the sole purposes
 * of debugging your products, maintaining your products, or enhancing the interoperability of your products with the
 * software, and specifically excludes the right to distribute the software outside of your company.
 * "Licensed patents" means any Licensor patent claims which read directly on the software as distributed by the Licensor
 * under this license.
 *
 * 2. Grant of Rights
 * (A) Copyright Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 * worldwide, royalty-free copyright license to reproduce the software for reference use.
 * (B) Patent Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 * worldwide, royalty-free patent license under licensed patents for reference use.
 *
 * 3. Limitations
 * (A) No Trademark License- This license does not grant you any rights to use the Licensor’s name, logo, or trademarks.
 * (B) If you begin patent litigation against the Licensor over patents that you think may apply to the software
 * (including a cross-claim or counterclaim in a lawsuit), your license to the software ends automatically.
 * (C) The software is licensed "as-is." You bear the risk of using it. The Licensor gives no express warranties,
 * guarantees or conditions. You may have additional consumer rights under your local laws which this license cannot
 * change. To the extent permitted under your local laws, the Licensor excludes the implied warranties of merchantability,
 * fitness for a particular purpose and non-infringement.
 */

package com.mycelium.wallet;

import java.io.Serializable;

import com.mrd.bitlib.crypto.InMemoryPrivateKey;
import com.mrd.bitlib.crypto.RandomSource;
import com.mrd.bitlib.crypto.SpinnerPrivateUri;
import com.mrd.bitlib.model.Address;
import com.mrd.bitlib.util.HashUtils;
import com.mrd.bitlib.util.HexUtils;

/**
 * Current Serialized Format: <HEX encoded 21 byte address>| <Bitcoin address
 * string>| <HEX encoded private key>| <HEX encoded public key>
 */
public class Record implements Serializable, Comparable<Record> {

   private static final long serialVersionUID = 1L;

   private static final int CURRENT_VERSION = 2;

   /**
    * A record backup state tells us what we know about the backup state of the
    * private key, currently only supported value is Unknown.
    */
   public enum BackupState {
      UNKNOWN(0);

      private final int _index;

      private BackupState(int index) {
         _index = index;
      }

      public int toInt() {
         return _index;
      }

      public static BackupState fromInt(int integer) {
         switch (integer) {
         case 0:
            return BackupState.UNKNOWN;
         default:
            return BackupState.UNKNOWN;
         }
      }

   }

   /**
    * A record tag identifies which set a record belongs to, currently Active or
    * Archive.
    */
   public enum Tag {
      UNKNOWN(0), ACTIVE(1), ARCHIVE(2);

      private final int _index;

      private Tag(int index) {
         _index = index;
      }

      public int toInt() {
         return _index;
      }

      public static Tag fromInt(int integer) {
         switch (integer) {
         case 0:
            return Tag.UNKNOWN;
         case 1:
            return Tag.ACTIVE;
         case 2:
            return Tag.ARCHIVE;
         default:
            return Tag.UNKNOWN;
         }
      }

   }

   public enum Source {
      UNKNOWN(0), VERSION_1(1), CREATED_PRIVATE_KEY(2), IMPORTED_BITCOIN_ADDRESS(3), IMPORTED_SPIA_PRIVATE_KEY(4), IMPORTED_MINI_PRIVATE_KEY(
            5), IMPORTED_SEED_PRIVATE_KEY(6), IMPORTED_BITCOIN_SPINNER_PRIVATE_KEY(7);

      private final int _index;

      private Source(int index) {
         _index = index;
      }

      public int toInt() {
         return _index;
      }

      public static Source fromInt(int integer) {
         switch (integer) {
         case 0:
            return Source.UNKNOWN;
         case 1:
            return Source.VERSION_1;
         case 2:
            return Source.CREATED_PRIVATE_KEY;
         case 3:
            return Source.IMPORTED_BITCOIN_ADDRESS;
         case 4:
            return Source.IMPORTED_SPIA_PRIVATE_KEY;
         case 5:
            return Source.IMPORTED_MINI_PRIVATE_KEY;
         case 6:
            return Source.IMPORTED_SEED_PRIVATE_KEY;
         case 7:
            return Source.IMPORTED_BITCOIN_SPINNER_PRIVATE_KEY;
         default:
            return Source.UNKNOWN;
         }
      }
   }

   public InMemoryPrivateKey key;
   public Address address;
   public long timestamp;
   public Source source;
   public Tag tag;
   public BackupState backupState;

   /**
    * Constructor used when creating a new record from a private key
    */
   private Record(InMemoryPrivateKey key, Source source) {
      this(key, Address.fromStandardPublicKey(key.getPublicKey(), Constants.network), System.currentTimeMillis(),
            source, Tag.ACTIVE, BackupState.UNKNOWN);
   }

   /**
    * Constructor used when creating a new record from Bitcoin address
    */
   private Record(Address address) {
      this(null, address, System.currentTimeMillis(), Source.IMPORTED_BITCOIN_ADDRESS, Tag.ACTIVE, BackupState.UNKNOWN);
   }

   /**
    * Constructor used when loading a persisted record
    */
   private Record(InMemoryPrivateKey key, Address address, long timestamp, Source source, Tag tag,
         BackupState backupState) {
      this.key = key;
      this.address = address;
      this.timestamp = timestamp;
      this.source = source;
      this.tag = tag;
      this.backupState = backupState;
   }

   public Record copy() {
      return new Record(key, address, timestamp, source, tag, backupState);
   }

   public void forgetPrivateKey() {
      key = null;
   }

   public boolean hasPrivateKey() {
      return key != null;
   }

   @Override
   public String toString() {
      return address.toString();
   }

   @Override
   public int compareTo(Record other) {

      // First sort on key / address
      if (hasPrivateKey() && !other.hasPrivateKey()) {
         return -1;
      } else if (!hasPrivateKey() && other.hasPrivateKey()) {
         return 1;
      }

      // Secondly sort on timestamp
      if (timestamp < other.timestamp) {
         return -1;
      } else if (timestamp > other.timestamp) {
         return 1;
      }

      // Thirdly sort on address
      return address.toString().compareTo(other.address.toString());

   }

   public String serialize() {

      // Serialization format is
      // "<version-string>|<timestamp-string>|<adress-bytes-hex>|<address-string>|<private-key-bytes-hex>|<public-key-bytes-hex>|<source-string>|<tag-string>"

      String versionString = Integer.toString(CURRENT_VERSION);
      String timestampString = Long.toString(timestamp);
      String addressBytesHex = HexUtils.toHex(address.getAllAddressBytes());
      String addressString = address.toString();
      String privateKeyBytesHex = hasPrivateKey() ? HexUtils.toHex(key.getPrivateKeyBytes()) : "";
      String publicKeyBytesHex = hasPrivateKey() ? HexUtils.toHex(key.getPublicKey().getPublicKeyBytes()) : "";
      String sourceString = Integer.toString(source.toInt());
      String tagString = Integer.toString(tag.toInt());
      String backupStateString = Integer.toString(backupState.toInt());

      StringBuilder sb = new StringBuilder();
      sb.append(versionString).append('|');
      sb.append(timestampString).append('|');
      sb.append(addressString).append('|');
      sb.append(addressBytesHex).append('|');
      sb.append(privateKeyBytesHex).append('|');
      sb.append(publicKeyBytesHex).append('|');
      sb.append(sourceString).append('|');
      sb.append(tagString).append('|');
      sb.append(backupStateString);

      return sb.toString();
   }

   public static Record fromSerializedString(String serialized) {
      try {
         String[] entries = chop(serialized, '|');
         if (entries.length < 5) {
            throw new RuntimeException("Record entry too small while parsing record.");
         }
         // Version 1 records have exactly 5 entries
         if (entries.length == 5) {
            return fromSerializedStringV1(entries);
         }

         // Later versions have a version as the first entry
         String versionString = entries[0];

         // May throw
         int version = Integer.parseInt(versionString);
         switch (version) {
         case 2:
            return fromSerializedStringV2(entries);
         default:
            throw new RuntimeException("Unknown record version number encountered: " + version);
         }
      } catch (Exception e) {
         // Bail out all the way, as we do not want to risk saving on top of the
         // current records if we cannot parse them
         throw new RuntimeException("Caught exception while parsing record.", e);
      }
   }

   private static Record fromSerializedStringV1(String[] entries) {
      try {
         if (entries.length != 5) {
            throw new RuntimeException("Record entry too small while parsing version 1 record.");
         }
         String timestampString = entries[0];
         String addressString = entries[1];
         String addressBytesHex = entries[2];
         String privateKeyBytesHex = entries[3];
         String publicKeyBytesHex = entries[4];

         // Timestamp
         if (timestampString.length() == 0) {
            throw new RuntimeException("Empty timestamp while parsing version 1 record.");
         }
         // May throw
         long timestamp = Long.parseLong(timestampString);

         // Address bytes and string
         if (addressBytesHex.length() == 0 || addressString.length() == 0) {
            throw new RuntimeException("Empty address while parsing version 1 record.");
         }
         // May throw
         Address address = new Address(HexUtils.toBytes(addressBytesHex), addressString);

         // Private key
         InMemoryPrivateKey key = null;
         if (privateKeyBytesHex.length() != 0 && publicKeyBytesHex.length() != 0) {
            // may throw
            key = new InMemoryPrivateKey(HexUtils.toBytes(privateKeyBytesHex), HexUtils.toBytes(publicKeyBytesHex));
         }

         // Upgrade version 1 to current version by defaulting missing values
         return new Record(key, address, timestamp, Source.VERSION_1, Tag.ACTIVE, BackupState.UNKNOWN);
      } catch (Exception e) {
         // Bail out all the way, as we do not want to risk saving on top of the
         // current records if we cannot parse them
         throw new RuntimeException("Exception  while parsing version 1 record.", e);
      }
   }

   private static Record fromSerializedStringV2(String[] entries) {
      try {
         if (entries.length != 9) {
            throw new RuntimeException("Record entry too small while parsing version 2 record.");
         }

         String versionString = entries[0];
         String timestampString = entries[1];
         String addressString = entries[2];
         String addressBytesHex = entries[3];
         String privateKeyBytesHex = entries[4];
         String publicKeyBytesHex = entries[5];
         String sourceString = entries[6];
         String tagString = entries[7];
         String backupStateString = entries[8];

         // Version
         int version = Integer.parseInt(versionString);
         if (version != 2) {
            throw new RuntimeException("Encountered version " + version + " record while parsing version 2 record.");
         }

         // Timestamp
         if (timestampString.length() == 0) {
            throw new RuntimeException("Empty timestamp while parsing version 2 record.");
         }
         // May throw
         long timestamp = Long.parseLong(timestampString);

         // Address bytes and string
         if (addressBytesHex.length() == 0 || addressString.length() == 0) {
            throw new RuntimeException("Empty address while parsing version 2 record.");
         }
         // May throw
         Address address = new Address(HexUtils.toBytes(addressBytesHex), addressString);

         // Private & Public key
         InMemoryPrivateKey key = null;
         if (privateKeyBytesHex.length() != 0 && publicKeyBytesHex.length() != 0) {
            // may throw
            key = new InMemoryPrivateKey(HexUtils.toBytes(privateKeyBytesHex), HexUtils.toBytes(publicKeyBytesHex));
         }

         // Source
         Source source = Source.fromInt(Integer.parseInt(sourceString)); // May
                                                                         // throw

         // Tag
         Tag tag = Tag.fromInt(Integer.parseInt(tagString)); // May
                                                             // throw

         // BackupState
         BackupState backupState = BackupState.fromInt(Integer.parseInt(backupStateString)); // May
                                                                                             // throw

         return new Record(key, address, timestamp, source, tag, backupState);
      } catch (Exception e) {
         // Bail out all the way, as we do not want to risk saving on top of the
         // current records if we cannot parse them
         throw new RuntimeException("Exception  while parsing version 2 record.", e);
      }
   }

   private static String[] chop(String string, char separator) {
      // Count chunks
      char[] chars = string.toCharArray();
      int chunkCount = 1;
      for (int i = 0; i < chars.length; i++) {
         if (chars[i] == separator) {
            chunkCount++;
         }
      }

      // Get chunks
      String[] chunks = new String[chunkCount];
      int currentIndex = 0;
      int endIndex;
      int chunkIndex = 0;
      while ((endIndex = string.indexOf(separator, currentIndex)) != -1) {
         chunks[chunkIndex++] = string.substring(currentIndex, endIndex);
         currentIndex = endIndex + 1;
      }
      // insert last chunk
      chunks[chunkIndex] = string.substring(currentIndex);
      return chunks;
   }

   @Override
   public int hashCode() {
      return address.hashCode();
   }

   @Override
   public boolean equals(Object obj) {
      if (obj == this) {
         return true;
      }
      if (!(obj instanceof Record)) {
         return false;
      }
      return this.address.equals(((Record) obj).address);
   }

   public static boolean isRecord(String string) {
      return fromString(string) != null;
   }

   public static Record fromString(String string) {

      if (string == null) {
         return null;
      }
      string = string.trim();

      Record record;

      // Do we have a Bitcoin address
      record = recordFromBitcoinAddressString(string);
      if (record != null) {
         return record;
      }

      // Do we have a Base58 private key
      record = recordFromBase58Key(string);
      if (record != null) {
         return record;
      }

      // Do we have a mini private key
      record = recordFromBase58KeyMimiFormat(string);
      if (record != null) {
         return record;
      }

      // Do we have a Bitcoin Spinner backup key
      record = recordFromBitcoinSpinnerBackup(string);
      if (record != null) {
         return record;
      }

      return null;
   }

   public static Record recordFromBitcoinAddressString(String string) {
      // Is it an address?
      Address address = Utils.addressFromString(string);
      if (address != null) {
         // We have an address
         return new Record(address);
      }
      return null;
   }

   public static Record recordFromBase58Key(String string) {
      // Is it a private key?
      try {
         InMemoryPrivateKey key = new InMemoryPrivateKey(string, Constants.network);
         return new Record(key, Source.IMPORTED_SPIA_PRIVATE_KEY);
      } catch (IllegalArgumentException e) {
         return null;
      }
   }

   public static Record recordFromBase58KeyMimiFormat(String string) {
      // Is it a mini private key on the format proposed by Casascius?
      if (string == null || string.length() < 2 || !string.startsWith("S")) {
         return null;
      }
      // Check that the string has a valid checksum
      String withQuestionMark = string + "?";
      byte[] checkHash = HashUtils.sha256(withQuestionMark.getBytes());
      if (checkHash[0] != 0x00) {
         return null;
      }
      // Now get the Sha256 hash and use it as the private key
      byte[] privateKeyBytes = HashUtils.sha256(string.getBytes());
      try {
         InMemoryPrivateKey key = new InMemoryPrivateKey(privateKeyBytes, false);
         return new Record(key, Source.IMPORTED_MINI_PRIVATE_KEY);
      } catch (IllegalArgumentException e) {
         return null;
      }
   }

   public static Record recordFromBitcoinSpinnerBackup(String string) {
      try {
         SpinnerPrivateUri spinnerKey = SpinnerPrivateUri.fromSpinnerUri(string);
         if (!spinnerKey.network.equals(Constants.network)) {
            return null;
         }
         return new Record(spinnerKey.key, Source.IMPORTED_BITCOIN_SPINNER_PRIVATE_KEY);
      } catch (IllegalArgumentException e) {
         return null;
      }
   }

   /**
    * Add a record from a seed using the same mechanism as brainwallet.org
    */
   public static Record recordFromRandomSeed(String seed, boolean compressed) {
      byte[] bytes = null;
      bytes = HashUtils.sha256(seed.getBytes());
      InMemoryPrivateKey key = new InMemoryPrivateKey(bytes, compressed);
      return new Record(key, Source.IMPORTED_SEED_PRIVATE_KEY);
   }

   public static Record createRandom(RandomSource randomSource) {
      return new Record(new InMemoryPrivateKey(randomSource, true), Source.CREATED_PRIVATE_KEY);
   }

}