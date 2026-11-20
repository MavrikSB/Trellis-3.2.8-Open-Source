package co.vine.android;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import co.vine.android.api.VineUser;
import co.vine.android.cache.image.ImageKey;
import co.vine.android.cache.image.UrlImage;
import co.vine.android.client.AppSessionListener;
import co.vine.android.util.Util;
import co.vine.android.util.analytics.FlurryUtils;
import co.vine.android.widgets.PromptDialogSupportFragment;
import com.twitter.android.widget.ItemPosition;
import java.util.ArrayList;
import java.util.HashMap;

public class FindFriendsNUXAddressFragment extends FindFriendsBaseFragment {
    private FindFriendsNUXActivity mActivity;
    private ArrayList<VineUser> mAddressFriends;
    private RelativeLayout mContactsCountContainer;
    private TextView mContactsText;
    private boolean mFilterLoaderIsReady;
    private Runnable mFilterRunnable = new RunnableC02121();
    private Handler mHandler;
    private TextView mSelectAllToggle;
    private boolean mSelected;

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        this.mActivity = (FindFriendsNUXActivity) activity;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            setFocused(args.getBoolean("take_focus", true));
        }
        this.mAppSessionListener = new FriendsNUXListener(this, null);
        this.mSelected = true;
        this.mAddressFriends = new ArrayList<>();
        this.mHandler = new Handler();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = super.onCreateView(inflater, container, savedInstanceState);
        this.mContactsCountContainer = (RelativeLayout) v.findViewById(R.id.contacts_count_container);
        this.mContactsText = (TextView) v.findViewById(R.id.contacts_text);
        this.mSelectAllToggle = (TextView) v.findViewById(R.id.select_all_toggle);
        this.mSelectAllToggle.setOnClickListener(this);
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isFocused()) {
            onMoveTo(-1);
        }
    }

    @Override
    public void onMoveTo(int oldPosition) {
        super.onMoveTo(oldPosition);
        this.mActivity.clearSearch();
        if (!this.mFetched) {
            PromptDialogSupportFragment p = PromptDialogSupportFragment.newInstance(1);
            p.setListener(this.mStoreContactDialogDoneListener);
            p.setMessage(R.string.store_contacts_desc);
            p.setTitle(R.string.store_contacts);
            p.setNegativeButton(R.string.no);
            p.setPositiveButton(R.string.ok);
            p.setCancelable(false);
            p.show(getFragmentManager());
        }
    }

    @Override
    public ItemPosition getFirstItemPosition() {
        return null;
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.follow_button) {
            FollowButtonViewHolder holder = (FollowButtonViewHolder) view.getTag();
            if (holder != null) {
                if (!holder.following) {
                    this.mActivity.addUserToFollow(holder.userId);
                    this.mFriendships.addFollowing(holder.userId);
                    this.mCursorAdapter.notifyDataSetChanged();
                    return;
                } else {
                    this.mActivity.removeUserToFollow(holder.userId);
                    this.mFriendships.removeFollowing(holder.userId);
                    this.mCursorAdapter.notifyDataSetChanged();
                    return;
                }
            }
            return;
        }
        if (id == R.id.select_all_toggle) {
            this.mSelected = !this.mSelected;
            this.mActivity.toggleFollowAll(this.mSelected, this.mAddressFriends, this.mFriendships);
            this.mSelectAllToggle.setText(this.mSelected ? R.string.deselect_all : R.string.select_all);
            this.mCursorAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void startProfileActivity(long userId) {
        ProfileActivity.start(getActivity(), userId, "Find Friends: Address", false);
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        this.mHandler.removeCallbacks(this.mFilterRunnable);
        this.mSearchQuery = s.toString();
        if (!TextUtils.isEmpty(this.mSearchQuery)) {
            this.mHandler.postDelayed(this.mFilterRunnable, 300L);
        } else {
            restartLoader(0);
        }
    }

    class RunnableC02121 implements Runnable {
        RunnableC02121() {
        }

        @Override
        public void run() {
            if (!FindFriendsNUXAddressFragment.this.mFilterLoaderIsReady) {
                FindFriendsNUXAddressFragment.this.initLoader(1);
                FindFriendsNUXAddressFragment.this.mFilterLoaderIsReady = true;
            } else {
                FindFriendsNUXAddressFragment.this.restartLoader(1);
            }
        }
    }

    private class FriendsNUXListener extends AppSessionListener {
        private FriendsNUXListener() {
        }

        FriendsNUXListener(FindFriendsNUXAddressFragment x0, RunnableC02121 x1) {
            this();
        }

        @Override
        public void onGetAddressFriendsComplete(String reqId, int statusCode, String reasonPhrase, int count, ArrayList<VineUser> users) {
            FindFriendsNUXAddressFragment.this.hideProgress(3);
            FindFriendsNUXAddressFragment.this.dismissProgressDialog();
            if (reqId != null && statusCode == 200) {
                if (count <= 0) {
                    FindFriendsNUXAddressFragment.this.showSadface(true);
                } else {
                    FindFriendsNUXAddressFragment.this.showSadface(false);
                    FindFriendsNUXAddressFragment.this.mContactsCountContainer.setVisibility(0);
                    FindFriendsNUXAddressFragment.this.mContactsText.setText(FindFriendsNUXAddressFragment.this.getString(R.string.find_friends_contacts_count, Integer.valueOf(count)));
                    FindFriendsNUXAddressFragment.this.mSelectAllToggle.setText(FindFriendsNUXAddressFragment.this.mSelected ? R.string.deselect_all : R.string.select_all);
                    FindFriendsNUXAddressFragment.this.mActivity.addUsersToFollow(users, FindFriendsNUXAddressFragment.this.mFriendships);
                    FindFriendsNUXAddressFragment.this.mAddressFriends.addAll(users);
                }
                FlurryUtils.trackFindFriendsAddressCount(count);
                return;
            }
            FindFriendsNUXAddressFragment.this.showSadface(true);
            Util.showCenteredToast(FindFriendsNUXAddressFragment.this.getActivity(), R.string.find_friends_address_error);
            FlurryUtils.trackFindFriendsAddressFailure();
        }

        @Override
        public void onRemoveUsersComplete(String reqId) {
            if (FindFriendsNUXAddressFragment.this.mCursorAdapter.getCursor() == null) {
                FindFriendsNUXAddressFragment.this.initLoader();
            }
        }

        @Override
        public void onPhotoImageLoaded(HashMap<ImageKey, UrlImage> images) {
            ((UsersAdapter) FindFriendsNUXAddressFragment.this.mCursorAdapter).setUserImages(images);
        }

        @Override
        public void onFollowComplete(String reqId, int statusCode, String reasonPhrase, long userId) {
            if (reqId != null && statusCode != 200) {
                FindFriendsNUXAddressFragment.this.mFriendships.removeFollowing(userId);
            }
        }

        @Override
        public void onUnFollowComplete(String reqId, int statusCode, String reasonPhrase, long userId) {
            if (reqId != null && statusCode != 200) {
                FindFriendsNUXAddressFragment.this.mFriendships.addFollowing(userId);
            }
        }
    }
}
