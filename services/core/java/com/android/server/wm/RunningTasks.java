/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;

import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.os.UserHandle;
import android.util.ArraySet;

import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

/**
 * Class for resolving the set of running tasks in the system.
 */
class RunningTasks {

    static final int FLAG_FILTER_ONLY_VISIBLE_RECENTS = 1;
    static final int FLAG_ALLOWED = 1 << 1;
    static final int FLAG_CROSS_USERS = 1 << 2;
    static final int FLAG_KEEP_INTENT_EXTRA = 1 << 3;

    // Comparator to sort by last active time (descending)
    private static final Comparator<Task> LAST_ACTIVE_TIME_COMPARATOR =
            (o1, o2) -> {
                return o1.lastActiveTime == o2.lastActiveTime
                        ? Integer.signum(o2.getPrefixOrderIndex() - o1.getPrefixOrderIndex()) :
                        Long.signum(o2.lastActiveTime - o1.lastActiveTime);
            };

    private final TreeSet<Task> mTmpSortedSet = new TreeSet<>(LAST_ACTIVE_TIME_COMPARATOR);

    private int mCallingUid;
    private int mUserId;
    private boolean mCrossUser;
    private ArraySet<Integer> mProfileIds;
    private boolean mAllowed;
    private boolean mFilterOnlyVisibleRecents;
    private RecentTasks mRecentTasks;
    private boolean mKeepIntentExtra;

    void getTasks(int maxNum, List<RunningTaskInfo> list, int flags, RecentTasks recentTasks,
            WindowContainer root, int callingUid, ArraySet<Integer> profileIds) {
        // Return early if there are no tasks to fetch
        if (maxNum <= 0) {
            return;
        }

        // Gather all of the tasks across all of the tasks, and add them to the sorted set
        mTmpSortedSet.clear();
        mCallingUid = callingUid;
        mUserId = UserHandle.getUserId(callingUid);
        mCrossUser = (flags & FLAG_CROSS_USERS) == FLAG_CROSS_USERS;
        mProfileIds = profileIds;
        mAllowed = (flags & FLAG_ALLOWED) == FLAG_ALLOWED;
        mFilterOnlyVisibleRecents =
                (flags & FLAG_FILTER_ONLY_VISIBLE_RECENTS) == FLAG_FILTER_ONLY_VISIBLE_RECENTS;
        mRecentTasks = recentTasks;
        mKeepIntentExtra = (flags & FLAG_KEEP_INTENT_EXTRA) == FLAG_KEEP_INTENT_EXTRA;

        if (root instanceof RootWindowContainer) {
            ((RootWindowContainer) root).forAllDisplays(dc -> {
                final Task focusedTask = dc.mFocusedApp != null ? dc.mFocusedApp.getTask() : null;
                processTaskInWindowContainer(dc, focusedTask);
            });
        } else {
            final DisplayContent dc = root.getDisplayContent();
            final Task focusedTask = dc != null
                    ? (dc.mFocusedApp != null ? dc.mFocusedApp.getTask() : null)
                    : null;
            final boolean rootContainsFocusedTask = focusedTask != null
                    && focusedTask.isDescendantOf(root);
            // update focusedTask's active time if root windowContainer contains it.
            processTaskInWindowContainer(root, rootContainsFocusedTask ? focusedTask : null);
        }

        // Take the first {@param maxNum} tasks and create running task infos for them
        final Iterator<Task> iter = mTmpSortedSet.iterator();
        while (iter.hasNext()) {
            if (maxNum == 0) {
                break;
            }

            final Task task = iter.next();
            list.add(createRunningTaskInfo(task));
            maxNum--;
        }
    }

    private void processTaskInWindowContainer(WindowContainer wc, @Nullable Task focusedTask) {
        final PooledConsumer c = PooledLambda.obtainConsumer(RunningTasks::processTask, this,
                PooledLambda.__(Task.class), focusedTask);
        wc.forAllLeafTasks(c, false);
        c.recycle();
    }

    private void processTask(Task task, @Nullable Task focusedTask) {
        if (task.getTopNonFinishingActivity() == null) {
            // Skip if there are no activities in the task
            return;
        }
        if (task.effectiveUid != mCallingUid) {
            if (task.mUserId != mUserId && !mCrossUser && !mProfileIds.contains(task.mUserId)) {
                // Skip if the caller does not have cross user permission or cannot access
                // the task's profile
                return;
            }
            if (!mAllowed) {
                // Skip if the caller isn't allowed to fetch this task
                return;
            }
        }
        if (mFilterOnlyVisibleRecents
                && task.getActivityType() != ACTIVITY_TYPE_HOME
                && task.getActivityType() != ACTIVITY_TYPE_RECENTS
                && !mRecentTasks.isVisibleRecentTask(task)) {
            // Skip if this task wouldn't be visibile (ever) from recents, with an exception for the
            // home & recent tasks
            return;
        }

        if (task.isVisible()) {
            // For the visible task, update the last active time so that it can be used to determine
            // the order of the tasks (it may not be set for newly created tasks)
            task.touchActiveTime();
            if (task != focusedTask && focusedTask != null) {
                // TreeSet doesn't allow the same value and make sure this task is lower than the
                // focused one.
                task.lastActiveTime--;
                // keep focused Task on top
                if (focusedTask.isVisible()) {
                    focusedTask.touchActiveTime();
                }
            }
        }

        mTmpSortedSet.add(task);
    }

    /** Constructs a {@link RunningTaskInfo} from a given {@param task}. */
    private RunningTaskInfo createRunningTaskInfo(Task task) {
        final RunningTaskInfo rti = new RunningTaskInfo();
        task.fillTaskInfo(rti, !mKeepIntentExtra);
        // Fill in some deprecated values
        rti.id = rti.taskId;
        return rti;
    }
}
