package com.hmdm.launcher.util;

import android.content.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.EffectiveWorkTimePolicy;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.json.WorkTimePolicyWrapper;

import java.util.Calendar;
import java.util.List;

public class WorkTimeManager {
    private static WorkTimeManager instance;
    private EffectiveWorkTimePolicy policy;
    private Boolean lastWorkTimeState = null;

    public static synchronized WorkTimeManager getInstance() {
        if (instance == null) {
            instance = new WorkTimeManager();
        }
        return instance;
    }
    
    public boolean shouldRefreshUI() {
        if (policy == null || !policy.isEnforcementEnabled()) {
            return false;
        }
        boolean currentWorkTimeState = isCurrentTimeWorkTime();
        if (lastWorkTimeState == null || lastWorkTimeState != currentWorkTimeState) {
            lastWorkTimeState = currentWorkTimeState;
            return true;
        }
        return false;
    }

    public void updatePolicy(Context context) {
        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        if (settingsHelper == null) return;
        
        ServerConfig config = settingsHelper.getConfig();
        if (config != null && config.getCustom1() != null) {
            try {
                // Ensure the string looks like JSON before parsing to avoid unnecessary exceptions
                String custom1 = config.getCustom1();
                if (custom1.trim().startsWith("{")) {
                    ObjectMapper mapper = new ObjectMapper();
                    WorkTimePolicyWrapper wrapper = mapper.readValue(custom1, WorkTimePolicyWrapper.class);
                    if ("worktime".equals(wrapper.getPluginId())) {
                        this.policy = wrapper.getPolicy();
                        return;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        this.policy = null;
    }

    public boolean isAppAllowed(String packageName) {
        if (policy == null || !policy.isEnforcementEnabled()) {
            return true;
        }

        // Check current time
        boolean isWorkTime = isCurrentTimeWorkTime();

        if (isWorkTime) {
            return isPackageAllowed(packageName, policy.getAllowedDuring());
        } else {
            return isPackageAllowed(packageName, policy.getAllowedOutside());
        }
    }

    private boolean isPackageAllowed(String packageName, List<String> list) {
        if (list == null) return false;
        if (list.contains("*")) return true;
        return list.contains(packageName);
    }

    private boolean isCurrentTimeWorkTime() {
        if (policy.getStartTime() == null || policy.getEndTime() == null) {
            return false;
        }

        Calendar now = Calendar.getInstance();

        // 1. Check Day of Week
        int dow = now.get(Calendar.DAY_OF_WEEK);
        // Calendar.MONDAY = 2, SUNDAY = 1
        // Server uses: Monday=1 (1<<0) ... Sunday=7 (1<<6)

        int serverDayIndex = 0;
        switch (dow) {
            case Calendar.MONDAY: serverDayIndex = 0; break;
            case Calendar.TUESDAY: serverDayIndex = 1; break;
            case Calendar.WEDNESDAY: serverDayIndex = 2; break;
            case Calendar.THURSDAY: serverDayIndex = 3; break;
            case Calendar.FRIDAY: serverDayIndex = 4; break;
            case Calendar.SATURDAY: serverDayIndex = 5; break;
            case Calendar.SUNDAY: serverDayIndex = 6; break;
        }

        int mask = 1 << serverDayIndex;
        if ((policy.getDaysOfWeek() & mask) == 0) {
            return false; // Not a working day
        }

        // 2. Check Time
        int currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int startMinute = parseTime(policy.getStartTime());
        int endMinute = parseTime(policy.getEndTime());

        // Simple range check (Start < End)
        // If Start > End (e.g. night shift 22:00 - 06:00), logic needs adjustment.
        // Assuming standard work hours for now based on doc simplicity.
        // But for completeness:
        if (startMinute <= endMinute) {
             return currentMinute >= startMinute && currentMinute < endMinute;
        } else {
             // Crossing midnight
             return currentMinute >= startMinute || currentMinute < endMinute;
        }
    }

    private int parseTime(String time) {
        try {
            String[] parts = time.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return 0;
        }
    }
}
