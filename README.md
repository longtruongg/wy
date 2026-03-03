# Monitor Service – Daily App Usage Tracker
Like Google Family Link, no account, just a service  
Test my device Pixel 7, its work, OEMs maybe :3

This Android foreground service monitors which apps are used in the foreground between **8:00 AM and 7:00 PM** every day.

- Detects when an app has been open for ≥ **10 minutes** 
- Sends HTTP POST notification to backend when threshold is reached
- Automatically starts/stops using exact alarms
- Requires user permissions: Usage Stats, Ignore Battery Optimization, Exact Alarms

## Features

- Daily active window: **08:00 – 19:00** (strict)
- Polling interval: 
- Query window for UsageStats:
- New App Installed 
- Reports long sessions via JSON POST to `http://your_backend_api/api/app-opened-long` (save it to excel file)
- Survives process death via alarms (requires permissions)

## Required Permissions & Settings

The app **must** have the following (user must grant them):

1. **Usage access**  
   → Settings → Apps → Special app access → Usage access

2. **Ignore battery optimizations** (critical!)  
   → Settings → Battery → App battery usage → Your app → Unrestricted

3. **Allow exact alarms** (Android 12+)  
   → Settings → Apps → Your app → Alarms & reminders → Allow

4. **Foreground service type: dataSync**  
   (Note: Android 15+ has ~6-hour daily limit → may need mid-day restart)
