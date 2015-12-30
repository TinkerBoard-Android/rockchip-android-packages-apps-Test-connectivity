/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.android_scripting.facade.telephony;

import android.app.Activity;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.telephony.CellInfo;
import android.telephony.CellLocation;
import android.telephony.ModemActivityInfo;
import android.telephony.NeighboringCellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.provider.Telephony;
import android.telephony.SubscriptionInfo;
import android.telecom.VideoProfile;
import android.telecom.TelecomManager;
import android.util.Base64;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyProperties;
import com.google.common.io.BaseEncoding;

import android.content.ContentValues;
import android.os.SystemProperties;

import com.googlecode.android_scripting.facade.AndroidFacade;
import com.googlecode.android_scripting.facade.EventFacade;
import com.googlecode.android_scripting.facade.FacadeManager;
import com.googlecode.android_scripting.facade.telephony.TelephonyStateListeners
                                                   .CallStateChangeListener;
import com.googlecode.android_scripting.facade.telephony.TelephonyStateListeners
                                                   .DataConnectionRealTimeInfoChangeListener;
import com.googlecode.android_scripting.facade.telephony.TelephonyStateListeners
                                                   .DataConnectionStateChangeListener;
import com.googlecode.android_scripting.facade.telephony.TelephonyStateListeners
                                                   .ServiceStateChangeListener;
import com.googlecode.android_scripting.facade.telephony.TelephonyStateListeners
                                                   .VoiceMailStateChangeListener;
import com.googlecode.android_scripting.jsonrpc.RpcReceiver;
import com.googlecode.android_scripting.rpc.Rpc;
import com.googlecode.android_scripting.rpc.RpcDefault;
import com.googlecode.android_scripting.rpc.RpcParameter;
import com.googlecode.android_scripting.Log;
import com.googlecode.android_scripting.MainThread;
import com.googlecode.android_scripting.rpc.RpcOptional;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.HashMap;

/**
 * Exposes TelephonyManager functionality.
 *
 * @author Damon Kohler (damonkohler@gmail.com)
 * @author Felix Arends (felix.arends@gmail.com)
 */
public class TelephonyManagerFacade extends RpcReceiver {

    private final Service mService;
    private final AndroidFacade mAndroidFacade;
    private final EventFacade mEventFacade;
    private final TelephonyManager mTelephonyManager;
    private final SubscriptionManager mSubscriptionManager;
    private List<SubscriptionInfo> mSubInfos;
    private HashMap<Integer, StateChangeListener> StateChangeListeners =
                             new HashMap<Integer, StateChangeListener>();

    private static final String[] sProjection = new String[] {
            Telephony.Carriers._ID, // 0
            Telephony.Carriers.NAME, // 1
            Telephony.Carriers.APN, // 2
            Telephony.Carriers.PROXY, // 3
            Telephony.Carriers.PORT, // 4
            Telephony.Carriers.USER, // 5
            Telephony.Carriers.SERVER, // 6
            Telephony.Carriers.PASSWORD, // 7
            Telephony.Carriers.MMSC, // 8
            Telephony.Carriers.MCC, // 9
            Telephony.Carriers.MNC, // 10
            Telephony.Carriers.NUMERIC, // 11
            Telephony.Carriers.MMSPROXY,// 12
            Telephony.Carriers.MMSPORT, // 13
            Telephony.Carriers.AUTH_TYPE, // 14
            Telephony.Carriers.TYPE, // 15
            Telephony.Carriers.PROTOCOL, // 16
            Telephony.Carriers.CARRIER_ENABLED, // 17
            Telephony.Carriers.BEARER_BITMASK, // 18
            Telephony.Carriers.ROAMING_PROTOCOL, // 19
            Telephony.Carriers.MVNO_TYPE, // 20
            Telephony.Carriers.MVNO_MATCH_DATA // 21
    };

    public TelephonyManagerFacade(FacadeManager manager) {
        super(manager);
        mService = manager.getService();
        mTelephonyManager =
                (TelephonyManager) mService.getSystemService(Context.TELEPHONY_SERVICE);
        mAndroidFacade = manager.getReceiver(AndroidFacade.class);
        mEventFacade = manager.getReceiver(EventFacade.class);
        mSubscriptionManager = SubscriptionManager.from(mService);
        mSubInfos = mSubscriptionManager.getAllSubscriptionInfoList();
        MainThread.run(manager.getService(), new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                // Creating listeners for all subscription IDs
                for (int i = 0; i < mSubInfos.size(); i++) {
                    int subId = mSubInfos.get(i).getSubscriptionId();
                    StateChangeListener mStateListeners =
                                                     new StateChangeListener();
                    mStateListeners.mServiceStateChangeListener =
                        new ServiceStateChangeListener(mEventFacade, subId);
                    mStateListeners.mDataConnectionStateChangeListener =
                        new DataConnectionStateChangeListener(mEventFacade,
                                                      mTelephonyManager, subId);
                    mStateListeners.mCallStateChangeListener =
                        new CallStateChangeListener(mEventFacade, subId);
                    mStateListeners.mDataConnectionRTInfoChangeListener =
                        new DataConnectionRealTimeInfoChangeListener(mEventFacade,
                                                                     subId);
                    mStateListeners.mVoiceMailStateChangeListener =
                        new VoiceMailStateChangeListener(mEventFacade, subId);

                    StateChangeListeners.put(subId, mStateListeners);
                }
                return null;
            }
        });
    }

    @Rpc(description = "Set network preference.")
    public boolean telephonySetPreferredNetworkTypes(
        @RpcParameter(name = "nwPreference") String nwPreference) {
        return telephonySetPreferredNetworkTypesForSubscription(nwPreference,
                SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Set network preference for subscription.")
    public boolean telephonySetPreferredNetworkTypesForSubscription(
            @RpcParameter(name = "nwPreference") String nwPreference,
            @RpcParameter(name = "subId") Integer subId) {
        int networkPreferenceInt = TelephonyUtils.getNetworkModeIntfromString(
            nwPreference);
        if (RILConstants.RIL_ERRNO_INVALID_RESPONSE != networkPreferenceInt) {
            return mTelephonyManager.setPreferredNetworkType(
                subId, networkPreferenceInt);
        } else {
            return false;
        }
    }

    @Rpc(description = "Get network preference.")
    public String telephonyGetPreferredNetworkTypes() {
        return telephonyGetPreferredNetworkTypesForSubscription(
                SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Get network preference for subscription.")
    public String telephonyGetPreferredNetworkTypesForSubscription(
            @RpcParameter(name = "subId") Integer subId) {
        int networkPreferenceInt = mTelephonyManager.getPreferredNetworkType(subId);
        return TelephonyUtils.getNetworkModeStringfromInt(networkPreferenceInt);
    }

    @Rpc(description = "Get current voice network type")
    public String telephonyGetCurrentVoiceNetworkType() {
        return telephonyGetCurrentVoiceNetworkTypeForSubscription(
                SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Get current voice network type for subscription")
    public String telephonyGetCurrentVoiceNetworkTypeForSubscription(
            @RpcParameter(name = "subId") Integer subId) {
        return TelephonyUtils.getNetworkTypeString(
            mTelephonyManager.getVoiceNetworkType(subId));
    }

    @Rpc(description = "Get current data network type")
    public String telephonyGetCurrentDataNetworkType() {
        return telephonyGetCurrentDataNetworkTypeForSubscription(
                SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Get current data network type for subscription")
    public String telephonyGetCurrentDataNetworkTypeForSubscription(
            @RpcParameter(name = "subId") Integer subId) {
        return TelephonyUtils.getNetworkTypeString(
            mTelephonyManager.getDataNetworkType(subId));
    }

    @Rpc(description = "Get if phone have voice capability")
    public boolean telephonyIsVoiceCapable() {
        return mTelephonyManager.isVoiceCapable();
    }

    @Rpc(description = "Get preferred network setting for " +
                       "default subscription ID .Return value is integer.")
    public int telephonyGetPreferredNetworkTypeInteger() {
        return telephonyGetPreferredNetworkTypeIntegerForSubscription(
                                         SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Get preferred network setting for " +
                       "specified subscription ID .Return value is integer.")
    public int telephonyGetPreferredNetworkTypeIntegerForSubscription(
               @RpcParameter(name = "subId") Integer subId) {
        return mTelephonyManager.getPreferredNetworkType(subId);
    }

    @Rpc(description = "Starts tracking call state change" +
                       "for default subscription ID.")
    public Boolean telephonyStartTrackingCallState() {
        return telephonyStartTrackingCallStateForSubscription(
                              SubscriptionManager.getDefaultVoiceSubId());
    }

    @Rpc(description = "Starts tracking call state change" +
                       "for specified subscription ID.")
    public Boolean telephonyStartTrackingCallStateForSubscription(
                @RpcParameter(name = "subId") Integer subId) {
        try {
            mTelephonyManager.listen(
                StateChangeListeners.get(subId).mCallStateChangeListener,
                CallStateChangeListener.sListeningStates);
            return true;
        } catch (Exception e) {
            Log.e("Invalid subscription ID");
            return false;
        }
    }

    @Rpc(description = "Turn on/off precise listening on fore/background or" +
                       " ringing calls for default voice subscription ID.")
    public Boolean telephonyAdjustPreciseCallStateListenLevel(String type,
                                                          Boolean listen) {
        return telephonyAdjustPreciseCallStateListenLevelForSubscription(type, listen,
                                 SubscriptionManager.getDefaultVoiceSubId());
    }

    @Rpc(description = "Turn on/off precise listening on fore/background or" +
                       " ringing calls for specified subscription ID.")
    public Boolean telephonyAdjustPreciseCallStateListenLevelForSubscription(String type,
                   Boolean listen,
                   @RpcParameter(name = "subId") Integer subId) {
        try {
            if (type.equals(TelephonyConstants.PRECISE_CALL_STATE_LISTEN_LEVEL_FOREGROUND)) {
                StateChangeListeners.get(subId).mCallStateChangeListener.listenForeground = listen;
            } else if (type.equals(TelephonyConstants.PRECISE_CALL_STATE_LISTEN_LEVEL_RINGING)) {
                StateChangeListeners.get(subId).mCallStateChangeListener.listenRinging = listen;
            } else if (type.equals(TelephonyConstants.PRECISE_CALL_STATE_LISTEN_LEVEL_BACKGROUND)) {
                StateChangeListeners.get(subId).mCallStateChangeListener.listenBackground = listen;
            }
            return true;
        } catch (Exception e) {
            Log.e("Invalid subscription ID");
            return false;
        }
    }

    @Rpc(description = "Stops tracking call state change " +
            "for default voice subscription ID.")
    public Boolean telephonyStopTrackingCallStateChange() {
        return telephonyStopTrackingCallStateChangeForSubscription(
                SubscriptionManager.getDefaultVoiceSubId());
    }

    @Rpc(description = "Stops tracking call state change " +
                       "for specified subscription ID.")
    public Boolean telephonyStopTrackingCallStateChangeForSubscription(
                   @RpcParameter(name = "subId") Integer subId) {
        try {
            mTelephonyManager.listen(
                StateChangeListeners.get(subId).mCallStateChangeListener,
                PhoneStateListener.LISTEN_NONE);
            return true;
        } catch (Exception e) {
            Log.e("Invalid subscription ID");
            return false;
        }
    }

    @Rpc(description = "Starts tracking data connection real time info change" +
                       "for default subscription ID.")
    public Boolean telephonyStartTrackingDataConnectionRTInfoChange() {
        return telephonyStartTrackingDataConnectionRTInfoChangeForSubscription(
                                 SubscriptionManager.getDefaultDataSubId());
    }

    @Rpc(description = "Starts tracking data connection real time info change" +
                       "for specified subscription ID.")
    public Boolean telephonyStartTrackingDataConnectionRTInfoChangeForSubscription(
                   @RpcParameter(name = "subId") Integer subId) {
        try {
            mTelephonyManager.listen(
                StateChangeListeners.get(subId).mDataConnectionRTInfoChangeListener,
                DataConnectionRealTimeInfoChangeListener.sListeningStates);
            return true;
        } catch (Exception e) {
            Log.e("Invalid subscription ID");
            return false;
        }
    }

    @Rpc(description = "Stops tracking data connection real time info change" +
                       "for default subscription ID.")
    public Boolean telephonyStopTrackingDataConnectionRTInfoChange() {
        return telephonyStopTrackingDataConnectionRTInfoChangeForSubscription(
                                 SubscriptionManager.getDefaultDataSubId());
    }

    @Rpc(description = "Stops tracking data connection real time info change" +
                       "for specified subscription ID.")
    public Boolean telephonyStopTrackingDataConnectionRTInfoChangeForSubscription(
                   @RpcParameter(name = "subId") Integer subId) {
        try {
            mTelephonyManager.listen(
                StateChangeListeners.get(subId).mDataConnectionRTInfoChangeListener,
                PhoneStateListener.LISTEN_NONE);
            return true;
        } catch (Exception e) {
            Log.e("Invalid subscription ID");
            return false;
        }
    }

    @Rpc(description = "Starts tracking data connection state change" +
                       "for default subscription ID..")
    public Boolean telephonyStartTrackingDataConnectionStateChange() {
        return telephonyStartTrackingDataConnectionStateChangeForSubscription(
                                 SubscriptionManager.getDefaultDataSubId());
    }

    @Rpc(description = "Starts tracking data connection state change" +
                       "for specified subscription ID.")
    public Boolean telephonyStartTrackingDataConnectionStateChangeForSubscription(
                   @RpcParameter(name = "subId") Integer subId) {
        try {
            mTelephonyManager.listen(
                StateChangeListeners.get(subId).mDataConnectionStateChangeListener,
                DataConnectionStateChangeListener.sListeningStates);
            return true;
        } catch (Exception e) {
            Log.e("Invalid subscription ID");
            return false;
        }
    }

    @Rpc(description = "Stops tracking data connection state change " +
                       "for default subscription ID..")
    public Boolean telephonyStopTrackingDataConnectionStateChange() {
        return telephonyStopTrackingDataConnectionStateChangeForSubscription(
                                 SubscriptionManager.getDefaultDataSubId());
    }

    @Rpc(description = "Stops tracking data connection state change " +
                       "for specified subscription ID..")
    public Boolean telephonyStopTrackingDataConnectionStateChangeForSubscription(
                   @RpcParameter(name = "subId") Integer subId) {
        try {
            mTelephonyManager.listen(
                StateChangeListeners.get(subId).mDataConnectionStateChangeListener,
                PhoneStateListener.LISTEN_NONE);
            return true;
        } catch (Exception e) {
            Log.e("Invalid subscription ID");
            return false;
        }
    }

    @Rpc(description = "Starts tracking service state change " +
                       "for default subscription ID.")
    public Boolean telephonyStartTrackingServiceStateChange() {
        return telephonyStartTrackingServiceStateChangeForSubscription(
                                 SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Starts tracking service state change " +
                       "for specified subscription ID.")
    public Boolean telephonyStartTrackingServiceStateChangeForSubscription(
                   @RpcParameter(name = "subId") Integer subId) {
         try {
            mTelephonyManager.listen(
                StateChangeListeners.get(subId).mServiceStateChangeListener,
                ServiceStateChangeListener.sListeningStates);
            return true;
        } catch (Exception e) {
            Log.e("Invalid subscription ID");
            return false;
        }
    }

    @Rpc(description = "Stops tracking service state change " +
                       "for default subscription ID.")
    public Boolean telephonyStopTrackingServiceStateChange() {
        return telephonyStopTrackingServiceStateChangeForSubscription(
                                 SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Stops tracking service state change " +
                       "for specified subscription ID.")
    public Boolean telephonyStopTrackingServiceStateChangeForSubscription(
                   @RpcParameter(name = "subId") Integer subId) {
        try {
            mTelephonyManager.listen(
                StateChangeListeners.get(subId).mServiceStateChangeListener,
                PhoneStateListener.LISTEN_NONE);
            return true;
        } catch (Exception e) {
            Log.e("Invalid subscription ID");
            return false;
        }
    }

    @Rpc(description = "Starts tracking voice mail state change " +
                       "for default subscription ID.")
    public Boolean telephonyStartTrackingVoiceMailStateChange() {
        return telephonyStartTrackingVoiceMailStateChangeForSubscription(
                                 SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Starts tracking voice mail state change " +
                       "for specified subscription ID.")
    public Boolean telephonyStartTrackingVoiceMailStateChangeForSubscription(
                   @RpcParameter(name = "subId") Integer subId) {
         try {
            mTelephonyManager.listen(
                StateChangeListeners.get(subId).mVoiceMailStateChangeListener,
                VoiceMailStateChangeListener.sListeningStates);
            return true;
        } catch (Exception e) {
            Log.e("Invalid subscription ID");
            return false;
        }
    }

    @Rpc(description = "Stops tracking voice mail state change " +
                       "for default subscription ID.")
    public Boolean telephonyStopTrackingVoiceMailStateChange() {
        return telephonyStopTrackingVoiceMailStateChangeForSubscription(
                                 SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Stops tracking voice mail state change " +
                       "for specified subscription ID.")
    public Boolean telephonyStopTrackingVoiceMailStateChangeForSubscription(
                   @RpcParameter(name = "subId") Integer subId) {
        try {
            mTelephonyManager.listen(
                StateChangeListeners.get(subId).mVoiceMailStateChangeListener,
                PhoneStateListener.LISTEN_NONE);
            return true;
        } catch (Exception e) {
            Log.e("Invalid subscription ID");
            return false;
        }
    }

    @Rpc(description = "Answers an incoming ringing call.")
    public void telephonyAnswerCall() throws RemoteException {
        mTelephonyManager.silenceRinger();
        mTelephonyManager.answerRingingCall();
    }

    @Rpc(description = "Returns the current cell location.")
    public CellLocation telephonyGetCellLocation() {
        return mTelephonyManager.getCellLocation();
    }

    @Rpc(description = "Returns the numeric name (MCC+MNC) of registered operator." +
                       "for default subscription ID")
    public String telephonyGetNetworkOperator() {
        return telephonyGetNetworkOperatorForSubscription(
                        SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Returns the numeric name (MCC+MNC) of registered operator" +
                       "for specified subscription ID.")
    public String telephonyGetNetworkOperatorForSubscription(
                  @RpcParameter(name = "subId") Integer subId) {
        return mTelephonyManager.getNetworkOperatorForSubscription(subId);
    }

    @Rpc(description = "Returns the alphabetic name of current registered operator" +
                       "for specified subscription ID.")
    public String telephonyGetNetworkOperatorName() {
        return telephonyGetNetworkOperatorNameForSubscription(
                        SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Returns the alphabetic name of registered operator " +
                       "for specified subscription ID.")
    public String telephonyGetNetworkOperatorNameForSubscription(
                  @RpcParameter(name = "subId") Integer subId) {
        return mTelephonyManager.getNetworkOperatorName(subId);
    }

    @Rpc(description = "Returns the current RAT in use on the device.+" +
                       "for default subscription ID")
    public String telephonyGetNetworkType() {

        Log.d("sl4a:getNetworkType() is deprecated!" +
                "Please use getVoiceNetworkType()" +
                " or getDataNetworkTpe()");

        return telephonyGetNetworkTypeForSubscription(
                       SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Returns the current RAT in use on the device" +
            " for a given Subscription.")
    public String telephonyGetNetworkTypeForSubscription(
                  @RpcParameter(name = "subId") Integer subId) {

        Log.d("sl4a:getNetworkTypeForSubscriber() is deprecated!" +
                "Please use getVoiceNetworkType()" +
                " or getDataNetworkTpe()");

        return TelephonyUtils.getNetworkTypeString(mTelephonyManager.getNetworkType(subId));
    }

    @Rpc(description = "Returns the current voice RAT for" +
            " the default voice subscription.")
    public String telephonyGetVoiceNetworkType() {
        return telephonyGetVoiceNetworkTypeForSubscription(
                         SubscriptionManager.getDefaultVoiceSubId());
    }

    @Rpc(description = "Returns the current voice RAT for" +
            " the specified voice subscription.")
    public String telephonyGetVoiceNetworkTypeForSubscription(
                  @RpcParameter(name = "subId") Integer subId) {
        return TelephonyUtils.getNetworkTypeString(mTelephonyManager.getVoiceNetworkType(subId));
    }

    @Rpc(description = "Returns the current data RAT for" +
            " the defaut data subscription")
    public String telephonyGetDataNetworkType() {
        return telephonyGetDataNetworkTypeForSubscription(
                         SubscriptionManager.getDefaultDataSubId());
    }

    @Rpc(description = "Returns the current data RAT for" +
            " the specified data subscription")
    public String telephonyGetDataNetworkTypeForSubscription(
                  @RpcParameter(name = "subId") Integer subId) {
        return TelephonyUtils.getNetworkTypeString(mTelephonyManager.getDataNetworkType(subId));
    }

    @Rpc(description = "Returns the device phone type.")
    public String telephonyGetPhoneType() {
        switch (mTelephonyManager.getPhoneType()) {
            case TelephonyManager.PHONE_TYPE_GSM:
                return TelephonyConstants.PHONE_TYPE_GSM;
            case TelephonyManager.PHONE_TYPE_NONE:
                return TelephonyConstants.PHONE_TYPE_NONE;
            case TelephonyManager.PHONE_TYPE_CDMA:
                return TelephonyConstants.PHONE_TYPE_CDMA;
            case TelephonyManager.PHONE_TYPE_SIP:
                return TelephonyConstants.PHONE_TYPE_SIP;
            default:
                return null;
        }
    }

    @Rpc(description = "Returns the MCC for default subscription ID")
    public String telephonyGetSimCountryIso() {
         return telephonyGetSimCountryIsoForSubscription(
                      SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Get the latest power consumption stats from the modem")
    public ModemActivityInfo telephonyGetModemActivityInfo() {
        ModemActivityInfo info = mTelephonyManager.getModemActivityInfo();
        return info;
    }

    @Rpc(description = "Returns the MCC for specified subscription ID")
    public String telephonyGetSimCountryIsoForSubscription(
                  @RpcParameter(name = "subId") Integer subId) {
        return mTelephonyManager.getSimCountryIso(subId);
    }

    @Rpc(description = "Returns the MCC+MNC for default subscription ID")
    public String telephonyGetSimOperator() {
        return telephonyGetSimOperatorForSubscription(
                  SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Returns the MCC+MNC for specified subscription ID")
    public String telephonyGetSimOperatorForSubscription(
                  @RpcParameter(name = "subId") Integer subId) {
        return mTelephonyManager.getSimOperator(subId);
    }

    @Rpc(description = "Returns the Service Provider Name (SPN)" +
                       "for default subscription ID")
    public String telephonyGetSimOperatorName() {
        return telephonyGetSimOperatorNameForSubscription(
                  SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Returns the Service Provider Name (SPN)" +
                       " for specified subscription ID.")
    public String telephonyGetSimOperatorNameForSubscription(
                  @RpcParameter(name = "subId") Integer subId) {
        return mTelephonyManager.getSimOperatorNameForSubscription(subId);
    }

    @Rpc(description = "Returns the serial number of the SIM for " +
                       "default subscription ID, or Null if unavailable")
    public String telephonyGetSimSerialNumber() {
        return telephonyGetSimSerialNumberForSubscription(
                  SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Returns the serial number of the SIM for " +
                       "specified subscription ID, or Null if unavailable")
    public String telephonyGetSimSerialNumberForSubscription(
                  @RpcParameter(name = "subId") Integer subId) {
        return mTelephonyManager.getSimSerialNumber(subId);
    }

    @Rpc(description = "Returns the state of the SIM card for default slot ID.")
    public String telephonyGetSimState() {
        return telephonyGetSimStateForSlotId(
                  mTelephonyManager.getDefaultSim());
    }

    @Rpc(description = "Returns the state of the SIM card for specified slot ID.")
    public String telephonyGetSimStateForSlotId(
                  @RpcParameter(name = "slotId") Integer slotId) {
        switch (mTelephonyManager.getSimState(slotId)) {
            case TelephonyManager.SIM_STATE_UNKNOWN:
                return TelephonyConstants.SIM_STATE_UNKNOWN;
            case TelephonyManager.SIM_STATE_ABSENT:
                return TelephonyConstants.SIM_STATE_ABSENT;
            case TelephonyManager.SIM_STATE_PIN_REQUIRED:
                return TelephonyConstants.SIM_STATE_PIN_REQUIRED;
            case TelephonyManager.SIM_STATE_PUK_REQUIRED:
                return TelephonyConstants.SIM_STATE_PUK_REQUIRED;
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED:
                return TelephonyConstants.SIM_STATE_NETWORK_LOCKED;
            case TelephonyManager.SIM_STATE_READY:
                return TelephonyConstants.SIM_STATE_READY;
            case TelephonyManager.SIM_STATE_NOT_READY:
                return TelephonyConstants.SIM_STATE_NOT_READY;
            case TelephonyManager.SIM_STATE_PERM_DISABLED:
                return TelephonyConstants.SIM_STATE_PERM_DISABLED;
            case TelephonyManager.SIM_STATE_CARD_IO_ERROR:
                return TelephonyConstants.SIM_STATE_CARD_IO_ERROR;
            default:
                Log.e("getSimStateForSlotId this should never happen. sim state:" +
                        mTelephonyManager.getSimState(slotId));
                return TelephonyConstants.SIM_STATE_UNKNOWN;
        }
    }

    @Rpc(description = "Get Authentication Challenge Response from a " +
            "given SIM Application")
    public String telephonyGetIccSimChallengeResponse(@RpcParameter(name = "appType") Integer appType,
            @RpcParameter(name = "hexChallenge") String hexChallenge) {
        return telephonyGetIccSimChallengeResponseForSubscription(
                SubscriptionManager.getDefaultSubId(), appType, hexChallenge);
    }

    @Rpc(description = "Get Authentication Challenge Response from a " +
            "given SIM Application for a specified Subscription")
    public String telephonyGetIccSimChallengeResponseForSubscription(
            @RpcParameter(name = "subId") Integer subId,
            @RpcParameter(name = "appType") Integer appType,
            @RpcParameter(name = "hexChallenge") String hexChallenge) {

        try {
            String b64Data = BaseEncoding.base64().encode(BaseEncoding.base16().decode(hexChallenge));
            String b64Result = mTelephonyManager.getIccSimChallengeResponse(subId, appType, b64Data);
            return (b64Result != null)
                    ? BaseEncoding.base16().encode(BaseEncoding.base64().decode(b64Result)) : null;
        } catch( Exception e) {
            Log.e("Exception in phoneGetIccSimChallengeResponseForSubscription" + e.toString());
            return null;
        }
    }

    @Rpc(description = "Returns the unique subscriber ID (such as IMSI) " +
            "for default subscription ID, or null if unavailable")
    public String telephonyGetSubscriberId() {
        return telephonyGetSubscriberIdForSubscription(
                SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Returns the unique subscriber ID (such as IMSI) " +
                       "for specified subscription ID, or null if unavailable")
    public String telephonyGetSubscriberIdForSubscription(
                  @RpcParameter(name = "subId") Integer subId) {
        return mTelephonyManager.getSubscriberId(subId);
    }

    @Rpc(description = "Retrieves the alphabetic id associated with the" +
                       " voice mail number for default subscription ID.")
    public String telephonyGetVoiceMailAlphaTag() {
        return telephonyGetVoiceMailAlphaTagForSubscription(
                   SubscriptionManager.getDefaultSubId());
    }


    @Rpc(description = "Retrieves the alphabetic id associated with the " +
                       "voice mail number for specified subscription ID.")
    public String telephonyGetVoiceMailAlphaTagForSubscription(
                  @RpcParameter(name = "subId") Integer subId) {
        return mTelephonyManager.getVoiceMailAlphaTag(subId);
    }

    @Rpc(description = "Returns the voice mail number " +
                       "for default subscription ID; null if unavailable.")
    public String telephonyGetVoiceMailNumber() {
        return telephonyGetVoiceMailNumberForSubscription(
                   SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Returns the voice mail number " +
                        "for specified subscription ID; null if unavailable.")
    public String telephonyGetVoiceMailNumberForSubscription(
                  @RpcParameter(name = "subId") Integer subId) {
        return mTelephonyManager.getVoiceMailNumber(subId);
    }

    @Rpc(description = "Get voice message count for specified subscription ID.")
    public Integer telephonyGetVoiceMailCountForSubscription(
                   @RpcParameter(name = "subId") Integer subId) {
        return mTelephonyManager.getVoiceMessageCount(subId);
    }

    @Rpc(description = "Get voice message count for default subscription ID.")
    public Integer telephonyGetVoiceMailCount() {
        return mTelephonyManager.getVoiceMessageCount();
    }

    @Rpc(description = "Returns true if the device is in  roaming state" +
                       "for default subscription ID")
    public Boolean telephonyCheckNetworkRoaming() {
        return telephonyCheckNetworkRoamingForSubscription(
                             SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Returns true if the device is in roaming state " +
                       "for specified subscription ID")
    public Boolean telephonyCheckNetworkRoamingForSubscription(
                   @RpcParameter(name = "subId") Integer subId) {
        return mTelephonyManager.isNetworkRoaming(subId);
    }

    @Rpc(description = "Returns the unique device ID such as MEID or IMEI " +
                       "for deault sim slot ID, null if unavailable")
    public String telephonyGetDeviceId() {
        return telephonyGetDeviceIdForSlotId(mTelephonyManager.getDefaultSim());
    }

    @Rpc(description = "Returns the unique device ID such as MEID or IMEI for" +
                       " specified slot ID, null if unavailable")
    public String telephonyGetDeviceIdForSlotId(
                  @RpcParameter(name = "slotId")
                  Integer slotId){
        return mTelephonyManager.getDeviceId(slotId);
    }

    @Rpc(description = "Returns the modem sw version, such as IMEI-SV;" +
                       " null if unavailable")
    public String telephonyGetDeviceSoftwareVersion() {
        return mTelephonyManager.getDeviceSoftwareVersion();
    }

    @Rpc(description = "Returns phone # string \"line 1\", such as MSISDN " +
                       "for default subscription ID; null if unavailable")
    public String telephonyGetLine1Number() {
        return telephonyGetLine1NumberForSubscription(
                        SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Returns phone # string \"line 1\", such as MSISDN " +
                       "for specified subscription ID; null if unavailable")
    public String telephonyGetLine1NumberForSubscription(
                  @RpcParameter(name = "subId") Integer subId) {
        return mTelephonyManager.getLine1NumberForSubscriber(subId);
    }

    @Rpc(description = "Returns the neighboring cell information of the device.")
    public List<NeighboringCellInfo> telephonyGetNeighboringCellInfo() {
        return mTelephonyManager.getNeighboringCellInfo();
    }

    @Rpc(description = "Returns all observed cell information from all radios"+
                       "on the device including the primary and neighboring cells")
    public List<CellInfo> telephonyGetAllCellInfo() {
        return mTelephonyManager.getAllCellInfo();
    }

    @Rpc(description = "Returns True if cellular data is enabled for" +
                       "default data subscription ID.")
    public Boolean telephonyIsDataEnabled() {
        return telephonyIsDataEnabledForSubscription(
                   SubscriptionManager.getDefaultDataSubId());
    }

    @Rpc(description = "Returns True if data connection is enabled.")
    public Boolean telephonyIsDataEnabledForSubscription(
                   @RpcParameter(name = "subId") Integer subId) {
        return mTelephonyManager.getDataEnabled(subId);
    }

    @Rpc(description = "Toggles data connection on /off for" +
                       " default data subscription ID.")
    public void telephonyToggleDataConnection(
                @RpcParameter(name = "enabled")
                @RpcOptional Boolean enabled) {
        telephonyToggleDataConnectionForSubscription(
                         SubscriptionManager.getDefaultDataSubId(), enabled);
    }

    @Rpc(description = "Toggles data connection on/off for" +
                       " specified subscription ID")
    public void telephonyToggleDataConnectionForSubscription(
                @RpcParameter(name = "subId") Integer subId,
                @RpcParameter(name = "enabled")
                @RpcOptional Boolean enabled) {
        if (enabled == null) {
            enabled = !telephonyIsDataEnabledForSubscription(subId);
        }
        mTelephonyManager.setDataEnabled(subId, enabled);
    }

    @Rpc(description = "Sets an APN and make that as preferred APN.")
    public void telephonySetAPN(@RpcParameter(name = "name") final String name,
                       @RpcParameter(name = "apn") final String apn,
                       @RpcParameter(name = "type") @RpcOptional @RpcDefault("")
                       final String type,
                       @RpcParameter(name = "subId") @RpcOptional Integer subId) {
        //TODO: b/26273471 Need to find out how to set APN for specific subId
        Uri uri;
        Cursor cursor;

        String mcc = "";
        String mnc = "";

        String numeric = SystemProperties.get(TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC);
        // MCC is first 3 chars and then in 2 - 3 chars of MNC
        if (numeric != null && numeric.length() > 4) {
            // Country code
            mcc = numeric.substring(0, 3);
            // Network code
            mnc = numeric.substring(3);
        }

        uri = mService.getContentResolver().insert(
                Telephony.Carriers.CONTENT_URI, new ContentValues());
        if (uri == null) {
            Log.w("Failed to insert new provider into " + Telephony.Carriers.CONTENT_URI);
            return;
        }

        cursor = mService.getContentResolver().query(uri, sProjection, null, null, null);
        cursor.moveToFirst();

        ContentValues values = new ContentValues();

        values.put(Telephony.Carriers.NAME, name);
        values.put(Telephony.Carriers.APN, apn);
        values.put(Telephony.Carriers.PROXY, "");
        values.put(Telephony.Carriers.PORT, "");
        values.put(Telephony.Carriers.MMSPROXY, "");
        values.put(Telephony.Carriers.MMSPORT, "");
        values.put(Telephony.Carriers.USER, "");
        values.put(Telephony.Carriers.SERVER, "");
        values.put(Telephony.Carriers.PASSWORD, "");
        values.put(Telephony.Carriers.MMSC, "");
        values.put(Telephony.Carriers.TYPE, type);
        values.put(Telephony.Carriers.MCC, mcc);
        values.put(Telephony.Carriers.MNC, mnc);
        values.put(Telephony.Carriers.NUMERIC, mcc + mnc);

        int ret = mService.getContentResolver().update(uri, values, null, null);
        Log.d("after update " + ret);
        cursor.close();

        // Make this APN as the preferred
        String where = "name=\"" + name + "\"";

        Cursor c = mService.getContentResolver().query(
                Telephony.Carriers.CONTENT_URI,
                new String[] {
                        "_id", "name", "apn", "type"
                }, where, null,
                Telephony.Carriers.DEFAULT_SORT_ORDER);
        if (c != null) {
            c.moveToFirst();
            String key = c.getString(0);
            final String PREFERRED_APN_URI = "content://telephony/carriers/preferapn";
            ContentResolver resolver = mService.getContentResolver();
            ContentValues prefAPN = new ContentValues();
            prefAPN.put("apn_id", key);
            resolver.update(Uri.parse(PREFERRED_APN_URI), prefAPN, null, null);
        }
        c.close();
    }

    @Rpc(description = "Returns the number of APNs defined")
    public int telephonyGetNumberOfAPNs(
               @RpcParameter(name = "subId")
               @RpcOptional Integer subId) {
        //TODO: b/26273471 Need to find out how to get Number of APNs for specific subId
        int result = 0;
        String where = "numeric=\"" + android.os.SystemProperties.get(
                        TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC, "") + "\"";

        Cursor cursor = mService.getContentResolver().query(
                Telephony.Carriers.CONTENT_URI,
                new String[] {"_id", "name", "apn", "type"}, where, null,
                Telephony.Carriers.DEFAULT_SORT_ORDER);

        if (cursor != null) {
            result = cursor.getCount();
        }
        cursor.close();
        return result;
    }

    @Rpc(description = "Returns the currently selected APN name")
    public String telephonyGetSelectedAPN(
                  @RpcParameter(name = "subId")
                  @RpcOptional Integer subId) {
        //TODO: b/26273471 Need to find out how to get selected APN for specific subId
        String key = null;
        int ID_INDEX = 0;
        final String PREFERRED_APN_URI = "content://telephony/carriers/preferapn";

        Cursor cursor = mService.getContentResolver().query(Uri.parse(PREFERRED_APN_URI),
                new String[] {"name"}, null, null, Telephony.Carriers.DEFAULT_SORT_ORDER);

        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            key = cursor.getString(ID_INDEX);
        }
        cursor.close();
        return key;
    }

    @Rpc(description = "Sets the preferred Network type")
    public void telephonySetPreferredNetwork(Integer networktype) {
        Integer subId = SubscriptionManager.getDefaultSubId();
        telephonySetPreferredNetworkForSubscription(subId, networktype);
    }

    @Rpc(description = "Sets the preferred network type for the given subId")
    public void telephonySetPreferredNetworkForSubscription(Integer subId, Integer networktype) {
        android.provider.Settings.Global.putInt(mService.getContentResolver(),
                android.provider.Settings.Global.PREFERRED_NETWORK_MODE + subId,
                networktype );
        mTelephonyManager.setPreferredNetworkType(subId, networktype);
    }

    @Rpc(description = "Returns the current data connection state")
    public String telephonyGetDataConnectionState() {
        int state = mTelephonyManager.getDataState();

        switch(state) {
            case TelephonyManager.DATA_DISCONNECTED:
                return TelephonyConstants.DATA_STATE_DISCONNECTED;
            case TelephonyManager.DATA_CONNECTING:
                return TelephonyConstants.DATA_STATE_CONNECTING;
            case TelephonyManager.DATA_CONNECTED:
                return TelephonyConstants.DATA_STATE_CONNECTED;
            case TelephonyManager.DATA_SUSPENDED:
                return TelephonyConstants.DATA_STATE_SUSPENDED;
            default:
                return TelephonyConstants.DATA_STATE_UNKNOWN;
        }
    }

    @Rpc(description = "Enables or Disables Video Calling()")
    public void telephonyEnableVideoCalling(boolean enable) {
        mTelephonyManager.enableVideoCalling(enable);
    }

    @Rpc(description = "Returns a boolean of whether or not " +
            "video calling setting is enabled by the user")
    public Boolean telephonyIsVideoCallingEnabled() {
        return mTelephonyManager.isVideoCallingEnabled();
    }

    @Rpc(description = "Returns a boolean of whether video calling is available for use")
    public Boolean telephonyIsVideoCallingAvailable() {
        return mTelephonyManager.isVideoTelephonyAvailable();
    }

    @Rpc(description = "Returns a boolean of whether or not the device is ims registered")
    public Boolean telephonyIsImsRegistered() {
        return mTelephonyManager.isImsRegistered();
    }

    @Rpc(description = "Returns a boolean of whether or not volte calling is available for use")
    public Boolean telephonyIsVolteAvailable() {
        return mTelephonyManager.isVolteAvailable();
    }

    @Rpc(description = "Returns a boolean of whether or not wifi calling is available for use")
    public Boolean telephonyIsWifiCallingAvailable() {
        return mTelephonyManager.isWifiCallingAvailable();
    }

    @Rpc(description = "Returns the service state for default subscription ID")
    public String telephonyGetServiceState() {
        //TODO: b/26273807 need to have framework API to get service state.
        return telephonyGetServiceStateForSubscription(
                                 SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Returns the service state for specified subscription ID")
    public String telephonyGetServiceStateForSubscription(
                  @RpcParameter(name = "subId") Integer subId) {
        //TODO: b/26273807 need to have framework API to get service state.
        return null;
    }

    @Rpc(description = "Returns the call state for default subscription ID")
    public String telephonyGetCallState() {
        return telephonyGetCallStateForSubscription(
                               SubscriptionManager.getDefaultSubId());
    }

    @Rpc(description = "Returns the call state for specified subscription ID")
    public String telephonyGetCallStateForSubscription(
                  @RpcParameter(name = "subId") Integer subId) {
        switch (mTelephonyManager.getCallState(subId)) {
            case TelephonyManager.CALL_STATE_IDLE:
                return TelephonyConstants.TELEPHONY_STATE_IDLE;
            case TelephonyManager.CALL_STATE_RINGING:
                return TelephonyConstants.TELEPHONY_STATE_RINGING;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                return TelephonyConstants.TELEPHONY_STATE_OFFHOOK;
            default:
                Log.e("getCallStateForSubscription this should never happen. state:" +
                        mTelephonyManager.getCallState(subId));
                return TelephonyConstants.TELEPHONY_STATE_UNKNOWN;
        }
    }

    @Rpc(description = "Returns the sim count.")
    public int telephonyGetSimCount() {
        return mTelephonyManager.getSimCount();
    }

    private static class StateChangeListener {
        private ServiceStateChangeListener mServiceStateChangeListener;
        private CallStateChangeListener mCallStateChangeListener;
        private DataConnectionStateChangeListener
                           mDataConnectionStateChangeListener;
        private DataConnectionRealTimeInfoChangeListener
                           mDataConnectionRTInfoChangeListener;
        private VoiceMailStateChangeListener
                           mVoiceMailStateChangeListener;
    }

    @Override
    public void shutdown() {
        for (int i = 0; i < mSubInfos.size(); i++) {
           int subId = mSubInfos.get(i).getSubscriptionId();
           telephonyStopTrackingCallStateChangeForSubscription(subId);
           telephonyStopTrackingDataConnectionRTInfoChangeForSubscription(subId);
           telephonyStopTrackingServiceStateChangeForSubscription(subId);
           telephonyStopTrackingDataConnectionStateChangeForSubscription(subId);
        }
    }
}