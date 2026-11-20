package co.vine.android;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;
import co.vine.android.client.AppSessionListener;
import co.vine.android.util.Util;
import co.vine.android.views.SwitchInterface;

public class PrivacyControlsFragment extends BaseControllerFragment {
    private static final String STATE_ACCEPT_OON = "state_accept_oon";
    private static final String STATE_ALLOW_ADDRESS_BOOK = "state_address_book";
    private static final String STATE_EMAIL_DISCOVERABLE = "state_email_discoverable";
    private static final String STATE_PHONE_DISCOVERABLE = "state_phone_discoverable";
    private static final String STATE_TWITTER_DISCOVERABLE = "state_twitter_discoverable";
    private static final int TYPE_ACCEPT_OON = 3;
    private static final int TYPE_ADDRESS_BOOK = 4;
    public static final int TYPE_EMAIL = 0;
    public static final int TYPE_PHONE = 1;
    public static final int TYPE_TWITTER = 2;
    private boolean mAcceptOon;
    private boolean mAllowAddressBook;
    private boolean mEmailDiscoverable;
    private boolean mPhoneDiscoverable;
    private boolean mTwitterDiscoverable;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.mAppSessionListener = new ContentControlsListener();
        if (savedInstanceState != null) {
            this.mAcceptOon = savedInstanceState.getBoolean(STATE_ACCEPT_OON, false);
            this.mAllowAddressBook = savedInstanceState.getBoolean(STATE_ALLOW_ADDRESS_BOOK, false);
            this.mEmailDiscoverable = savedInstanceState.getBoolean(STATE_EMAIL_DISCOVERABLE, true);
            this.mPhoneDiscoverable = savedInstanceState.getBoolean(STATE_PHONE_DISCOVERABLE, true);
            this.mTwitterDiscoverable = savedInstanceState.getBoolean(STATE_TWITTER_DISCOVERABLE, true);
            return;
        }
        SharedPreferences prefs = Util.getDefaultSharedPrefs(getActivity());
        this.mAcceptOon = prefs.getBoolean(Settings.PREF_ACCEPT_OON, true);
        this.mAllowAddressBook = prefs.getBoolean(Settings.PREF_ENABLE_ADDRESS_BOOK, false);
        this.mEmailDiscoverable = prefs.getBoolean(Settings.PREF_EMAIL_DISCOVERABLE, true);
        this.mPhoneDiscoverable = prefs.getBoolean(Settings.PREF_PHONE_DISCOVERABLE, true);
        this.mTwitterDiscoverable = prefs.getBoolean(Settings.PREF_TWITTER_DISCOVERABLE, true);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_ACCEPT_OON, this.mAcceptOon);
        outState.putBoolean(STATE_ALLOW_ADDRESS_BOOK, this.mAllowAddressBook);
        outState.putBoolean(STATE_EMAIL_DISCOVERABLE, this.mEmailDiscoverable);
        outState.putBoolean(STATE_PHONE_DISCOVERABLE, this.mPhoneDiscoverable);
        outState.putBoolean(STATE_TWITTER_DISCOVERABLE, this.mTwitterDiscoverable);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ViewGroup vg = (ViewGroup) inflater.inflate(R.layout.settings_sub, container, false);
        addSwitcher(inflater, vg, R.string.settings_accepts_oon, R.string.settings_accepts_oon_desc, this.mAcceptOon, 3);
        addSwitcher(inflater, vg, R.string.settings_address_book, R.string.settings_address_book_desc, this.mAllowAddressBook, 4);
        addSwitcher(inflater, vg, R.string.email_discoverability, R.string.email_discoverability_desc, this.mEmailDiscoverable, 0);
        addSwitcher(inflater, vg, R.string.phone_discoverability, R.string.phone_discoverability_desc, this.mPhoneDiscoverable, 1);
        addSwitcher(inflater, vg, R.string.twitter_discoverability, R.string.twitter_discoverability_desc, this.mTwitterDiscoverable, 2);
        return vg;
    }

    private void addSwitcher(LayoutInflater inflater, ViewGroup parent, int labelString, int descString, boolean initialCondition, int type) {
        View view = inflater.inflate(R.layout.settings_switch_row, parent, false);
        SwitchInterface switcher = (SwitchInterface) view.findViewById(R.id.switcher);
        if (initialCondition) {
            switcher.setChecked(true);
        }
        switcher.setOnCheckedChangeListener(new PrivacyControlOnCheckChangedListener(this, type, null));
        ((TextView) view.findViewById(R.id.label)).setText(labelString);
        ((TextView) view.findViewById(R.id.description)).setText(descString);
        parent.addView(view);
        inflater.inflate(R.layout.settings_sub_divider, parent, true);
    }

    class PrivacyControlOnCheckChangedListener implements CompoundButton.OnCheckedChangeListener {
        private final int mActionCode;

        PrivacyControlOnCheckChangedListener(PrivacyControlsFragment x0, int x1, Object x2) {
            this(x1);
        }

        private PrivacyControlOnCheckChangedListener(int actionCode) {
            this.mActionCode = actionCode;
        }

        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (this.mActionCode == 3) {
                PrivacyControlsFragment.this.mAcceptOon = isChecked;
                PrivacyControlsFragment.this.mAppController.updateAcceptOon(PrivacyControlsFragment.this.mAcceptOon);
                return;
            }
            if (this.mActionCode == 4) {
                PrivacyControlsFragment.this.mAllowAddressBook = isChecked;
                PrivacyControlsFragment.this.mAppController.updateEnableAddressBook(PrivacyControlsFragment.this.mAllowAddressBook);
                return;
            }
            if (this.mActionCode == 0) {
                PrivacyControlsFragment.this.mEmailDiscoverable = isChecked;
                PrivacyControlsFragment.this.mAppController.updateDiscoverability(0, PrivacyControlsFragment.this.mEmailDiscoverable);
            } else if (this.mActionCode == 1) {
                PrivacyControlsFragment.this.mPhoneDiscoverable = isChecked;
                PrivacyControlsFragment.this.mAppController.updateDiscoverability(1, PrivacyControlsFragment.this.mPhoneDiscoverable);
            } else if (this.mActionCode == 2) {
                PrivacyControlsFragment.this.mTwitterDiscoverable = isChecked;
                PrivacyControlsFragment.this.mAppController.updateDiscoverability(2, PrivacyControlsFragment.this.mTwitterDiscoverable);
            }
        }
    }

    class ContentControlsListener extends AppSessionListener {
        ContentControlsListener() {
        }

        @Override
        public void onUpdateAcceptOonComplete(String reqId, int statusCode, String reasonPhrase, boolean acceptOon) {
            Activity activity = PrivacyControlsFragment.this.getActivity();
            if (statusCode == 200) {
                SharedPreferences.Editor editor = Util.getDefaultSharedPrefs(activity).edit();
                editor.putBoolean(Settings.PREF_ACCEPT_OON, acceptOon);
                editor.apply();
                return;
            }
            Util.showCenteredToast(activity, R.string.privacy_controls_update_fail);
        }

        @Override
        public void onUpdateEnableAddressBookComplete(String reqId, int statusCode, String reasonPhrase, boolean enableAddressBook) {
            Activity activity = PrivacyControlsFragment.this.getActivity();
            if (statusCode == 200) {
                SharedPreferences.Editor editor = Util.getDefaultSharedPrefs(PrivacyControlsFragment.this.getActivity()).edit();
                editor.putBoolean(Settings.PREF_ENABLE_ADDRESS_BOOK, enableAddressBook);
                editor.apply();
                if (enableAddressBook) {
                    PrivacyControlsFragment.this.mAppController.sendAddressBook();
                    return;
                }
                return;
            }
            Util.showCenteredToast(activity, R.string.privacy_controls_update_fail);
        }

        @Override
        public void onUpdateDiscoverability(String reqId, int statusCode, String reasonPhrase, int type, boolean enable) {
            String pref;
            Activity activity = PrivacyControlsFragment.this.getActivity();
            if (statusCode == 200) {
                if (type == 0) {
                    pref = Settings.PREF_EMAIL_DISCOVERABLE;
                } else if (type == 1) {
                    pref = Settings.PREF_PHONE_DISCOVERABLE;
                } else {
                    pref = Settings.PREF_TWITTER_DISCOVERABLE;
                }
                Util.getDefaultSharedPrefs(PrivacyControlsFragment.this.getActivity()).edit().putBoolean(pref, enable).apply();
                return;
            }
            Util.showCenteredToast(activity, R.string.privacy_controls_update_fail);
        }
    }
}
