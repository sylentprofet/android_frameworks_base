/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import java.util.ArrayList;
import java.util.List;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader.TileMode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.systemui.R;

public class RecentAppsPanel extends RelativeLayout implements StatusBarPanel, OnItemClickListener {
    private static final int GLOW_PADDING = 15;
    private static final String TAG = "RecentAppsPanel";
    private static final boolean DEBUG = TabletStatusBar.DEBUG;
    private static final int DISPLAY_TASKS = 20;
    private static final int MAX_TASKS = DISPLAY_TASKS + 1; // allow extra for non-apps
    private TabletStatusBar mBar;
    private ArrayList<ActivityDescription> mActivityDescriptions;
    private int mIconDpi;
    private View mRecentsScrim;
    private View mRecentsGlowView;
    private ListView mRecentsContainer;
    private Bitmap mGlowBitmap;
    private boolean mShowing;
    private Choreographer mChoreo;
    private View mRecentsDismissButton;
    private ActvityDescriptionAdapter mListAdapter;
    protected int mLastVisibleItem;

    static class ActivityDescription {
        int id;
        Bitmap thumbnail; // generated by Activity.onCreateThumbnail()
        Drawable icon; // application package icon
        String label; // application package label
        CharSequence description; // generated by Activity.onCreateDescription()
        Intent intent; // launch intent for application
        Matrix matrix; // arbitrary rotation matrix to correct orientation
        String packageName; // used to override animations (see onClick())
        int position; // position in list

        public ActivityDescription(Bitmap _thumbnail,
                Drawable _icon, String _label, CharSequence _desc, Intent _intent,
                int _id, int _pos, String _packageName)
        {
            thumbnail = _thumbnail;
            icon = _icon;
            label = _label;
            description = _desc;
            intent = _intent;
            id = _id;
            position = _pos;
            packageName = _packageName;
        }
    };

    /* package */ final static class ViewHolder {
        private ImageView thumbnailView;
        private ImageView iconView;
        private TextView labelView;
        private TextView descriptionView;
        private ActivityDescription activityDescription;
    }

    /* package */ final class ActvityDescriptionAdapter extends BaseAdapter {
        private LayoutInflater mInflater;

        public ActvityDescriptionAdapter(Context context) {
            mInflater = LayoutInflater.from(context);
        }

        public int getCount() {
            return mActivityDescriptions != null ? mActivityDescriptions.size() : 0;
        }

        public Object getItem(int position) {
            return position; // we only need the index
        }

        public long getItemId(int position) {
            return position; // we just need something unique for this position
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.status_bar_recent_item, null);
                holder = new ViewHolder();
                holder.thumbnailView = (ImageView) convertView.findViewById(R.id.app_thumbnail);
                holder.iconView = (ImageView) convertView.findViewById(R.id.app_icon);
                holder.labelView = (TextView) convertView.findViewById(R.id.app_label);
                holder.descriptionView = (TextView) convertView.findViewById(R.id.app_description);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            // activityId is reverse since most recent appears at the bottom...
            final int activityId = mActivityDescriptions.size() - position - 1;

            final ActivityDescription activityDescription = mActivityDescriptions.get(activityId);
            final Bitmap thumb = activityDescription.thumbnail;
            holder.thumbnailView.setImageBitmap(compositeBitmap(mGlowBitmap, thumb));
            holder.iconView.setImageDrawable(activityDescription.icon);
            holder.labelView.setText(activityDescription.label);
            holder.descriptionView.setText(activityDescription.description);
            holder.thumbnailView.setTag(activityDescription);
            holder.activityDescription = activityDescription;

            return convertView;
        }
    }

    public boolean isInContentArea(int x, int y) {
        // use mRecentsContainer's exact bounds to determine horizontal position
        final int l = mRecentsContainer.getLeft();
        final int r = mRecentsContainer.getRight();
        // use surrounding mRecentsGlowView's position in parent determine vertical bounds
        final int t = mRecentsGlowView.getTop();
        final int b = mRecentsGlowView.getBottom();
        return x >= l && x < r && y >= t && y < b;
    }

    public void show(boolean show, boolean animate) {
        if (animate) {
            if (mShowing != show) {
                mShowing = show;
                if (show) {
                    setVisibility(View.VISIBLE);
                }
                mChoreo.startAnimation(show);
            }
        } else {
            mShowing = show;
            setVisibility(show ? View.VISIBLE : View.GONE);
            mChoreo.jumpTo(show);
        }
    }

    /**
     * We need to be aligned at the bottom.  LinearLayout can't do this, so instead,
     * let LinearLayout do all the hard work, and then shift everything down to the bottom.
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mChoreo.setPanelHeight(mRecentsContainer.getHeight());
    }

    /**
     * Whether the panel is showing, or, if it's animating, whether it will be
     * when the animation is done.
     */
    public boolean isShowing() {
        return mShowing;
    }

    private static class Choreographer implements Animator.AnimatorListener {
        // should group this into a multi-property animation
        private static final int OPEN_DURATION = 136;
        private static final int CLOSE_DURATION = 250;

        boolean mVisible;
        int mPanelHeight;
        View mRootView;
        View mScrimView;
        View mContentView;
        AnimatorSet mContentAnim;

        // the panel will start to appear this many px from the end
        final int HYPERSPACE_OFFRAMP = 200;

        public Choreographer(View root, View scrim, View content) {
            mRootView = root;
            mScrimView = scrim;
            mContentView = content;
        }

        void createAnimation(boolean appearing) {
            float start, end;

            if (DEBUG) Log.e(TAG, "createAnimation()", new Exception());

            // 0: on-screen
            // height: off-screen
            float y = mContentView.getTranslationY();
            if (appearing) {
                // we want to go from near-the-top to the top, unless we're half-open in the right
                // general vicinity
                start = (y < HYPERSPACE_OFFRAMP) ? y : HYPERSPACE_OFFRAMP;
                end = 0;
            } else {
                start = y;
                end = y + HYPERSPACE_OFFRAMP;
            }

            Animator posAnim = ObjectAnimator.ofFloat(mContentView, "translationY",
                    start, end);
            posAnim.setInterpolator(appearing
                    ? new android.view.animation.DecelerateInterpolator(2.5f)
                    : new android.view.animation.AccelerateInterpolator(2.5f));

            Animator glowAnim = ObjectAnimator.ofFloat(mContentView, "alpha",
                    mContentView.getAlpha(), appearing ? 1.0f : 0.0f);
            glowAnim.setInterpolator(appearing
                    ? new android.view.animation.AccelerateInterpolator(1.0f)
                    : new android.view.animation.DecelerateInterpolator(1.0f));

            Animator bgAnim = ObjectAnimator.ofInt(mScrimView.getBackground(),
                    "alpha", appearing ? 0 : 255, appearing ? 255 : 0);

            mContentAnim = new AnimatorSet();
            mContentAnim
                    .play(bgAnim)
                    .with(glowAnim)
                    .with(posAnim);
            mContentAnim.setDuration(appearing ? OPEN_DURATION : CLOSE_DURATION);
            mContentAnim.addListener(this);
        }

        void startAnimation(boolean appearing) {
            if (DEBUG) Slog.d(TAG, "startAnimation(appearing=" + appearing + ")");

            createAnimation(appearing);

            mContentView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            mContentAnim.start();

            mVisible = appearing;
        }

        void jumpTo(boolean appearing) {
            mContentView.setTranslationY(appearing ? 0 : mPanelHeight);
        }

        public void setPanelHeight(int h) {
            if (DEBUG) Slog.d(TAG, "panelHeight=" + h);
            mPanelHeight = h;
        }

        public void onAnimationCancel(Animator animation) {
            if (DEBUG) Slog.d(TAG, "onAnimationCancel");
            // force this to zero so we close the window
            mVisible = false;
        }

        public void onAnimationEnd(Animator animation) {
            if (DEBUG) Slog.d(TAG, "onAnimationEnd");
            if (!mVisible) {
                mRootView.setVisibility(View.GONE);
            }
            mContentView.setLayerType(View.LAYER_TYPE_NONE, null);
            mContentAnim = null;
        }

        public void onAnimationRepeat(Animator animation) {
        }

        public void onAnimationStart(Animator animation) {
        }
    }

    public void setBar(TabletStatusBar bar) {
        mBar = bar;
    }

    public RecentAppsPanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentAppsPanel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        Resources res = context.getResources();
        boolean xlarge = (res.getConfiguration().screenLayout
                & Configuration.SCREENLAYOUT_SIZE_MASK) == Configuration.SCREENLAYOUT_SIZE_XLARGE;

        mIconDpi = xlarge ? DisplayMetrics.DENSITY_HIGH : res.getDisplayMetrics().densityDpi;
        mGlowBitmap = BitmapFactory.decodeResource(res, R.drawable.recents_thumbnail_bg);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Keep track of the last visible item in the list so we can restore it
        // to the bottom when the orientation changes.
        int childCount = mRecentsContainer.getChildCount();
        if (childCount > 0) {
            mLastVisibleItem = mRecentsContainer.getFirstVisiblePosition() + childCount - 1;
            View view = mRecentsContainer.getChildAt(childCount - 1);
            final int distanceFromBottom = mRecentsContainer.getHeight() - view.getTop();
            //final int distanceFromBottom = view.getHeight() + BOTTOM_OFFSET;

            // This has to happen post-layout, so run it "in the future"
            post(new Runnable() {
                public void run() {
                    mRecentsContainer.setSelectionFromTop(mLastVisibleItem,
                            mRecentsContainer.getHeight() - distanceFromBottom);
                }
            });
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        LayoutInflater inflater = (LayoutInflater)
        mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        mRecentsContainer = (ListView) findViewById(R.id.recents_container);
        View footer = inflater.inflate(R.layout.status_bar_recent_panel_footer,
                mRecentsContainer, false);
        mRecentsContainer.setScrollbarFadingEnabled(true);
        mRecentsContainer.addFooterView(footer, null, false);
        mRecentsContainer.setAdapter(mListAdapter = new ActvityDescriptionAdapter(mContext));
        mRecentsContainer.setOnItemClickListener(this);

        mRecentsGlowView = findViewById(R.id.recents_glow);
        mRecentsScrim = (View) findViewById(R.id.recents_bg_protect);
        mChoreo = new Choreographer(this, mRecentsScrim, mRecentsGlowView);
        mRecentsDismissButton = findViewById(R.id.recents_dismiss_button);
        mRecentsDismissButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                hide(true);
            }
        });

        // In order to save space, we make the background texture repeat in the Y direction
        if (mRecentsScrim != null && mRecentsScrim.getBackground() instanceof BitmapDrawable) {
            ((BitmapDrawable) mRecentsScrim.getBackground()).setTileModeY(TileMode.REPEAT);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (DEBUG) Log.v(TAG, "onVisibilityChanged(" + changedView + ", " + visibility + ")");
        if (visibility == View.VISIBLE && changedView == this) {
            refreshApplicationList();
            post(new Runnable() {
                public void run() {
                    mRecentsContainer.setSelection(mActivityDescriptions.size() - 1);
                }
            });
        }
    }

    private Drawable getFullResDefaultActivityIcon() {
        return getFullResIcon(Resources.getSystem(),
                com.android.internal.R.mipmap.sym_def_app_icon);
    }

    private Drawable getFullResIcon(Resources resources, int iconId) {
        return resources.getDrawableForDensity(iconId, mIconDpi);
    }

    private Drawable getFullResIcon(ResolveInfo info, PackageManager packageManager) {
        Resources resources;
        try {
            resources = packageManager.getResourcesForApplication(
                    info.activityInfo.applicationInfo);
        } catch (PackageManager.NameNotFoundException e) {
            resources = null;
        }
        if (resources != null) {
            int iconId = info.activityInfo.getIconResource();
            if (iconId != 0) {
                return getFullResIcon(resources, iconId);
            }
        }
        return getFullResDefaultActivityIcon();
    }

    private ArrayList<ActivityDescription> getRecentTasks() {
        ArrayList<ActivityDescription> activityDescriptions = new ArrayList<ActivityDescription>();
        final PackageManager pm = mContext.getPackageManager();
        final ActivityManager am = (ActivityManager)
                mContext.getSystemService(Context.ACTIVITY_SERVICE);

        final List<ActivityManager.RecentTaskInfo> recentTasks =
                am.getRecentTasks(MAX_TASKS, ActivityManager.RECENT_IGNORE_UNAVAILABLE
                        | ActivityManager.TASKS_GET_THUMBNAILS);

        ActivityInfo homeInfo = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .resolveActivityInfo(pm, 0);

        int numTasks = recentTasks.size();

        // skip the first activity - assume it's either the home screen or the current app.
        final int first = 1;
        for (int i = first, index = 0; i < numTasks && (index < MAX_TASKS); ++i) {
            final ActivityManager.RecentTaskInfo recentInfo = recentTasks.get(i);

            Intent intent = new Intent(recentInfo.baseIntent);
            if (recentInfo.origActivity != null) {
                intent.setComponent(recentInfo.origActivity);
            }

            // Skip the current home activity.
            if (homeInfo != null
                    && homeInfo.packageName.equals(intent.getComponent().getPackageName())
                    && homeInfo.name.equals(intent.getComponent().getClassName())) {
                continue;
            }

            intent.setFlags((intent.getFlags()&~Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            final ResolveInfo resolveInfo = pm.resolveActivity(intent, 0);
            if (resolveInfo != null) {
                final ActivityInfo info = resolveInfo.activityInfo;
                final String title = info.loadLabel(pm).toString();
                // Drawable icon = info.loadIcon(pm);
                Drawable icon = getFullResIcon(resolveInfo, pm);
                int id = recentTasks.get(i).id;
                if (title != null && title.length() > 0 && icon != null) {
                    if (DEBUG) Log.v(TAG, "creating activity desc for id=" + id + ", label=" + title);
                    ActivityDescription item = new ActivityDescription(
                            am.getTaskThumbnail(recentInfo.persistentId),
                            icon, title, recentInfo.description, intent, id,
                            index, info.packageName);
                    activityDescriptions.add(item);
                    ++index;
                } else {
                    if (DEBUG) Log.v(TAG, "SKIPPING item " + id);
                }
            }
        }
        return activityDescriptions;
    }

    ActivityDescription findActivityDescription(int id)
    {
        ActivityDescription desc = null;
        for (int i = 0; i < mActivityDescriptions.size(); i++) {
            ActivityDescription item = mActivityDescriptions.get(i);
            if (item != null && item.id == id) {
                desc = item;
                break;
            }
        }
        return desc;
    }

    private void refreshApplicationList() {
        mActivityDescriptions = getRecentTasks();
        mListAdapter.notifyDataSetInvalidated();
        if (mActivityDescriptions.size() > 0) {
            mLastVisibleItem = mActivityDescriptions.size() - 1; // scroll to bottom after reloading
            updateUiElements(getResources().getConfiguration());
        } else {
            // Immediately hide this panel
            hide(false);
        }
    }

    private Bitmap compositeBitmap(Bitmap background, Bitmap thumbnail) {
        Bitmap outBitmap = background.copy(background.getConfig(), true);
        if (thumbnail != null) {
            Canvas canvas = new Canvas(outBitmap);
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setFilterBitmap(true);
            paint.setAlpha(255);
            final int srcWidth = thumbnail.getWidth();
            final int height = thumbnail.getHeight();
            final int srcHeight = srcWidth > height ? height
                    : (height - height * srcWidth / height);
            canvas.drawBitmap(thumbnail,
                    new Rect(0, 0, srcWidth-1, srcHeight-1),
                    new RectF(GLOW_PADDING,
                            GLOW_PADDING - 7.0f,
                            outBitmap.getWidth() - GLOW_PADDING + 3.0f,
                            outBitmap.getHeight() - GLOW_PADDING + 7.0f), paint);
        }
        return outBitmap;
    }

    private void updateUiElements(Configuration config) {
        final int items = mActivityDescriptions.size();

        mRecentsContainer.setVisibility(items > 0 ? View.VISIBLE : View.GONE);
        mRecentsGlowView.setVisibility(items > 0 ? View.VISIBLE : View.GONE);
    }

    private void hide(boolean animate) {
        if (!animate) {
            setVisibility(View.GONE);
        }
        mBar.animateCollapse();
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        ActivityDescription ad = ((ViewHolder) view.getTag()).activityDescription;
        final ActivityManager am = (ActivityManager)
                getContext().getSystemService(Context.ACTIVITY_SERVICE);
        if (ad.id >= 0) {
            // This is an active task; it should just go to the foreground.
            am.moveTaskToFront(ad.id, ActivityManager.MOVE_TASK_WITH_HOME);
        } else {
            Intent intent = ad.intent;
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
                    | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
            if (DEBUG) Log.v(TAG, "Starting activity " + intent);
            getContext().startActivity(intent);
        }
        hide(true);
    }
}
