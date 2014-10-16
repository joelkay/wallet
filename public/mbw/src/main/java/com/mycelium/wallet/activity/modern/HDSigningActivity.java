/*
 * Copyright 2013, 2014 Megion Research and Development GmbH
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

package com.mycelium.wallet.activity.modern;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.mrd.bitlib.crypto.InMemoryPrivateKey;
import com.mrd.bitlib.model.Address;
import com.mycelium.wallet.MbwManager;
import com.mycelium.wallet.R;
import com.mycelium.wallet.Utils;
import com.mycelium.wallet.activity.MessageSigningActivity;
import com.mycelium.wapi.model.TransactionOutputSummary;
import com.mycelium.wapi.wallet.AesKeyCipher;
import com.mycelium.wapi.wallet.KeyCipher;
import com.mycelium.wapi.wallet.WalletAccount;
import com.mycelium.wapi.wallet.bip44.Bip44Account;

import java.util.List;
import java.util.UUID;

public class HDSigningActivity extends Activity {

   private static final LinearLayout.LayoutParams WCWC = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
   private static final LinearLayout.LayoutParams FPWC = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1);


   private SigningClickListener _signingClickListener;
   private MbwManager _mbwManager;
   private UUID _accountid;

   @Override
   public void onCreate(Bundle savedInstanceState) {
      this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
      super.onCreate(savedInstanceState);
      setContentView(R.layout.hd_signing_activity);

      _signingClickListener = new SigningClickListener();
      _mbwManager = MbwManager.getInstance(this.getApplication());
      _accountid = (UUID) getIntent().getSerializableExtra("account");

      updateUi();
   }

   private void updateUi() {

      LinearLayout addressview = (LinearLayout) findViewById(R.id.listPrivateKeyAddresses);
      Bip44Account account = (Bip44Account) _mbwManager.getWalletManager(false).getAccount(_accountid);

      //sort addresses by alphabet for easier selection
      List<Address> addresses = Utils.sortAAddresses(account.getAllAddresses());

      for (Address address : addresses) {
         addressview.addView(getItemView(address));
      }
   }

   private View getItemView(Address address) {
      // Create vertical linear layout for address
      LinearLayout ll = new LinearLayout(this);
      ll.setOrientation(LinearLayout.VERTICAL);
      ll.setLayoutParams(WCWC);
      ll.setPadding(10, 10, 10, 10);

      String addressString = address.toString();

      // Add address chunks
      String[] chunks = Utils.stringChopper(addressString, 12);
      ll.addView(getAddressChunk(chunks[0], -1, address));
      ll.addView(getAddressChunk(chunks[1], -1, address));
      ll.addView(getAddressChunk(chunks[2], 0, address));

      //Make address clickable
      ll.setOnClickListener(_signingClickListener);
      ll.setTag(address);

      return ll;
   }

   private TextView getAddressChunk(String chunk, int padding, Object tag) {
      TextView tv = new TextView(this);
      tv.setLayoutParams(FPWC);
      tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
      setLinkText(tv, chunk);
      tv.setTypeface(Typeface.MONOSPACE);
      tv.setPadding(0, 0, 0, padding);
      tv.setTag(tag);

      return tv;
   }

   private void setLinkText(TextView tv, String text) {
      SpannableString link = new SpannableString(text);
      link.setSpan(new UnderlineSpan(), 0, text.length(), 0);
      tv.setText(link);
      tv.setTextColor(getResources().getColor(R.color.brightblue));
   }

   private class SigningClickListener implements View.OnClickListener {

      @Override
      public void onClick(View v) {
         if (v.getTag() == null) {
            return;
         }
         Address address = (Address) v.getTag();
         Bip44Account account = (Bip44Account) _mbwManager.getWalletManager(false).getAccount(_accountid);
         InMemoryPrivateKey key;
         try {
            key = account.getPrivateKeyForAddress(address, AesKeyCipher.defaultKeyCipher());
         } catch (KeyCipher.InvalidKeyCipher invalidKeyCipher) {
            throw new RuntimeException(invalidKeyCipher);
         }
         MessageSigningActivity.callMe(HDSigningActivity.this, key);
      }
   }
}