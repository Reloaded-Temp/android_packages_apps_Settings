/*
 * Copyright (c) 2020, The Linux Foundation. All rights reserved
 * Not a contribution
 *
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.development;

import static androidx.lifecycle.Lifecycle.Event.ON_DESTROY;
import static androidx.lifecycle.Lifecycle.Event.ON_PAUSE;
import static androidx.lifecycle.Lifecycle.Event.ON_RESUME;
import static androidx.lifecycle.Lifecycle.Event.ON_START;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.preference.PreferenceManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.Log;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settings.R;
import com.android.settings.network.SubscriptionUtil;
import com.android.settings.network.SubscriptionsChangeListener;
import com.android.settings.network.telephony.MobileNetworkUtils;
import com.android.settingslib.core.AbstractPreferenceController;

import com.qti.extphone.Client;
import com.qti.extphone.ExtTelephonyManager;
import com.qti.extphone.ExtPhoneCallbackBase;
import com.qti.extphone.NrConfig;
import com.qti.extphone.Status;
import com.qti.extphone.ServiceCallback;
import com.qti.extphone.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This populates the entries on a page which lists all available mobile subscriptions. Each entry
 * has the name of the subscription with some subtext giving additional detail, and clicking on the
 * entry brings you to a details page for that network.
 */
public class Prefer5GNetworkListController extends AbstractPreferenceController implements
        LifecycleObserver, ListPreference.OnPreferenceChangeListener,
        SubscriptionsChangeListener.SubscriptionsChangeListenerClient {
    private static final String TAG = "Prefer5gNetworkListCtlr";

    @VisibleForTesting
    static final String KEY_ADD_MORE = "prefer_5g_add_more";
    private static final int NR_MODE_NSA_SA = 0;
    private static final int NR_MODE_NSA = 1;
    private static final int NR_MODE_SA = 2;
    private static final int EVENT_SET_NR_CONFIG_STATUS = 101;
    private static final int EVENT_GET_NR_CONFIG_STATUS = 102;

    private Client mClient;
    private Context mContext;
    private String mPackageName;
    private SharedPreferences mSharedPreferences;
    private SubscriptionManager mSubscriptionManager;
    private SubscriptionsChangeListener mChangeListener;
    private PreferenceScreen mPreferenceScreen;
    private TelephonyManager mTelephonyManager;
    private Map<Integer, ListPreference> mPreferences;
    private List<ListPreference> mPreferenceList;

    private int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private int mPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
    private int userPrefNrConfig;
    private ExtTelephonyManager mExtTelephonyManager;
    private boolean mServiceConnected;
    private boolean mRetry;

    private ExtPhoneCallbackBase mCallback = new ExtPhoneCallbackBase() {
        @Override
        public void onSetNrConfig(int slotId, Token token, Status status) throws
                RemoteException {
            Log.d(TAG, "onSetNrConfig: slotId = " + slotId + " token = " + token + " status = " +
                    status);
            if (status.get() == Status.SUCCESS) {
                updateSharedPreference(slotId, userPrefNrConfig);
            }
            mMainThreadHandler.sendMessage(mMainThreadHandler
                    .obtainMessage(EVENT_SET_NR_CONFIG_STATUS, slotId, -1));
        }

        @Override
        public void onNrConfigStatus(int slotId, Token token, Status status, NrConfig nrConfig)
                throws RemoteException {
            Log.d(TAG, "onNrConfigStatus: slotId = " + slotId + " token = " + token + " status = " +
                    status + " nrConfig = " + nrConfig);
            if (status.get() == Status.SUCCESS) {
                updateSharedPreference(slotId, nrConfig.get());
                mMainThreadHandler.sendMessage(mMainThreadHandler
                        .obtainMessage(EVENT_GET_NR_CONFIG_STATUS, slotId, -1));
            }
        }
    };

    private Handler mMainThreadHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "handleMessage msg.what = " + msg.what);
            switch (msg.what) {
                case EVENT_SET_NR_CONFIG_STATUS:
                case EVENT_GET_NR_CONFIG_STATUS: {
                    int slotId = msg.arg1;
                    if (mPreferenceScreen != null) {
                        updatePreferenceForSlot(slotId);
                    }
                    break;
                }
            }
        }
    };

    private void updateSharedPreference(int slotId, int nrConfig) {
        if (mSharedPreferences != null) {
            mSharedPreferences.edit().putInt("nr_mode_" + slotId, nrConfig).apply();
        }
    }


    public Prefer5GNetworkListController(Context context, Lifecycle lifecycle) {
        super(context);
        Log.i(TAG, "constructor");
        mContext = context;
        mPackageName = mContext.getPackageName();
        mSharedPreferences = mContext.getSharedPreferences(mContext.getPackageName(),
                mContext.MODE_PRIVATE);
        mSubscriptionManager = context.getSystemService(SubscriptionManager.class);
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mChangeListener = new SubscriptionsChangeListener(context, this);
        mPreferences = new ArrayMap<>();
        if (mExtTelephonyManager == null) {
            mExtTelephonyManager = ExtTelephonyManager.getInstance(
                    mContext.getApplicationContext());
        }
        Log.d(TAG, "Connect to ExtTelephony bound service...");
        mExtTelephonyManager.connectService(mServiceCallback);

        lifecycle.addObserver(this);
    }

    private ServiceCallback mServiceCallback = new ServiceCallback() {
        @Override
        public void onConnected() {
            Log.d(TAG, "ExtTelephony Service connected");
            mServiceConnected = true;
            mClient = mExtTelephonyManager.registerCallback(mPackageName, mCallback);
            Log.d(TAG, "Client = " + mClient);
            if (mRetry) {
                for (int slotId = 0; slotId < mTelephonyManager.getPhoneCount();
                        slotId++) {
                    mRetry = false;
                    Token token = mExtTelephonyManager.queryNrConfig(slotId, mClient);
                    Log.d(TAG, "queryNrConfig: " + token);
                }
            }
        }

        @Override
        public void onDisconnected() {
            Log.d(TAG, "ExtTelephony Service disconnected...");
            if (mServiceConnected) {
                mServiceConnected = false;
                mClient = null;
            }
        }
    };

    @OnLifecycleEvent(ON_RESUME)
    public void onResume() {
        Log.i(TAG, "onResume");
        mChangeListener.start();
        update();
    }

    @OnLifecycleEvent(ON_PAUSE)
    public void onPause() {
        mChangeListener.stop();
    }

    @OnLifecycleEvent(ON_DESTROY)
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        mExtTelephonyManager.unRegisterCallback(mCallback);
        mExtTelephonyManager.disconnectService();
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        Log.i(TAG, "displayPreference");
        mPreferenceScreen = screen;
        mPreferenceScreen.findPreference(KEY_ADD_MORE).setVisible(
                MobileNetworkUtils.showEuiccSettings(mContext));
    }

    private void update() {
        if (mPreferenceScreen == null) {
            return;
        }

        // Since we may already have created some preferences previously, we first grab the list of
        // those, then go through the current available subscriptions making sure they are all
        // present in the screen, and finally remove any now-outdated ones.
        final Map<Integer, ListPreference> existingPreferences = mPreferences;
        mPreferences = new ArrayMap<>();
        mPreferenceList = new ArrayList<>();

        for (int slotId = 0; slotId < mTelephonyManager.getPhoneCount();
                slotId++) {
            SubscriptionInfo info = mSubscriptionManager.
                getActiveSubscriptionInfoForSimSlotIndex(slotId);
            if (info != null) {
                final int subId = info.getSubscriptionId();
                ListPreference pref = existingPreferences.remove(subId);
                if (pref == null) {
                    pref = new ListPreference(mPreferenceScreen.getContext());
                    mPreferenceScreen.addPreference(pref);
                }
                pref.setTitle(info.getDisplayName());
                pref.setOrder(slotId);
                pref.setDialogTitle("Select NR Mode For Slot " + slotId);

                pref.setOnPreferenceChangeListener(this);
                pref.setEntries(R.array.preferred_5g_network_mode_choices);
                pref.setEntryValues(R.array.preferred_5g_network_mode_values);
                if (mServiceConnected && mClient != null) {
                    mRetry = false;
                    Token token = mExtTelephonyManager.queryNrConfig(slotId, mClient);
                    Log.d(TAG, "queryNrConfig: " + token);
                } else {
                    mRetry = true;
                }

                mPreferences.put(subId, pref);
                mPreferenceList.add(pref);
            } else {
                Log.d(TAG, "sub info is null, add null preference for slot: " + slotId);
                mPreferenceList.add(slotId, null);
            }
        }
        for (Preference pref : existingPreferences.values()) {
            mPreferenceScreen.removePreference(pref);
        }
    }

    private void updatePreferenceForSlot(int slotId) {
        int nrConfig = mSharedPreferences.getInt("nr_mode_" + slotId,
                NrConfig.NR_CONFIG_COMBINED_SA_NSA);
        String text = mContext.getString(getSummaryResId(nrConfig));
        Log.d(TAG, "updatePreferenceForSlot for " + slotId + " ,nr mode is " + nrConfig +
                " , set summary to " + text);
        ListPreference pref = mPreferenceList.get(slotId);
        if (pref != null) {
            pref.setSummary(text);
            pref.setValue(Integer.toString(nrConfig));
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object object) {
        final int newNrMode = Integer.parseInt((String) object);
        final int slotId = mPreferenceList.indexOf(preference);
        Log.i(TAG, "onPreferenceChange for slot: " + slotId + ", setNrConfig: " + newNrMode);
        userPrefNrConfig = newNrMode;
        if (mServiceConnected && mClient != null) {
            Token token = mExtTelephonyManager.setNrConfig(
                    slotId, new NrConfig(newNrMode), mClient);
            Log.d(TAG, "setNrConfig: " + token);
        }
        final ListPreference listPreference = (ListPreference) preference;
        String summary = mContext.getString(getSummaryResId(newNrMode));
        listPreference.setSummary(summary);
        return true;
    }

    private int getSummaryResId(int nrMode) {
        if (nrMode == NrConfig.NR_CONFIG_COMBINED_SA_NSA) {
            return R.string.nr_nsa_sa;
        } else if (nrMode == NrConfig.NR_CONFIG_NSA) {
            return R.string.nr_nsa;
        } else if (nrMode == NrConfig.NR_CONFIG_SA) {
            return R.string.nr_sa;
        } else {
            return R.string.nr_nsa_sa;
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getPreferenceKey() {
        return null;
    }

    @Override
    public void onAirplaneModeChanged(boolean airplaneModeEnabled) {
    }

    @Override
    public void onSubscriptionsChanged() {
        boolean isSubChanged = false;
        final List<SubscriptionInfo> subs = SubscriptionUtil.getAvailableSubscriptions(
                mContext);
        for (SubscriptionInfo info : subs) {
            if (info == null) return;

            final int subId = info.getSubscriptionId();
            if (mPreferences.get(subId) == null) {
                isSubChanged = true;
            }
        }

        if (isSubChanged) {
            Log.d(TAG, "sub changed, will update preference");
            update();
        }
    }
}
