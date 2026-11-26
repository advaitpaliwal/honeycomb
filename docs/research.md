# Research: context, notifications and ranking

Open questions for anyone who wants to improve how Honeycomb picks what to show. These are starting points, not
features. Cite primary sources, keep observations apart from guesses, and say which capabilities an ordinary Android
app has and which need a system component.

## Context

List the signals available: time, calendar, media, app usage, notifications and location. For each, record the
permission it needs, how fresh it is, its battery cost and what happens when the permission is denied. Start with
Android's [UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager)
and the [calendar provider](https://developer.android.com/identity/providers/calendar-provider).
The dynamic grid today ranks apps by seven days of usage, or by launches Honeycomb recorded itself; any experiment
should keep that baseline available.

## Notifications

Measure what can happen after a notification is posted, not only whether it can be removed. The sound or banner may
already have played. Keep calls, alarms, ongoing work and user control explicit, keep the original notification, and
check how each idea fails without a network.

Primary APIs: [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
and [NotificationAssistantService](https://developer.android.com/reference/android/service/notification/NotificationAssistantService).
The second is a system integration that an app installed from a store cannot rely on.

## Ranking

Study [Apple's Smart Stack user guide](https://support.apple.com/guide/watch/see-widgets-in-the-smart-stack-apdecf142fb9/watchos)
and [WidgetKit timeline relevance](https://developer.apple.com/documentation/widgetkit/timelineentry/relevance).
Do not infer Apple's internal algorithm from a screenshot or from an API that shows only part of the system. Compare
any proposal with Honeycomb's current Smart Stack order and its handling of dismissed cards, and judge it on
usefulness, stability, unwanted interruptions, how easily it can be explained, privacy and user overrides.
