package com.adobe.phonegap.push;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.support.v4.app.NotificationCompat;
import android.text.Html;
import android.util.Log;

import com.google.android.gcm.GCMBaseIntentService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

@SuppressLint("NewApi")
public class GCMIntentService extends GCMBaseIntentService implements PushConstants {

    private static final String LOG_TAG = "PushPlugin_GCMIntentService";
    private static HashMap<Integer, ArrayList<String>> messageMap = new HashMap<Integer, ArrayList<String>>();
    private boolean summarize = false;
    private boolean shouldSummarize = false;

    public void setNotification(int notId, String message){
        ArrayList<String> messageList = messageMap.get(notId);
        if(messageList == null) {
            messageList = new ArrayList<String>();
            messageMap.put(notId, messageList);
        }

        if(message.isEmpty()){
            messageList.clear();
        }else{
            messageList.add(message);
        }
    }

    public GCMIntentService() {
        super("GCMIntentService");
    }

    @Override
    public void onRegistered(Context context, String regId) {

        Log.v(LOG_TAG, "onRegistered: " + regId);

        try {
            JSONObject json = new JSONObject().put(REGISTRATION_ID, regId);

            Log.v(LOG_TAG, "onRegistered: " + json.toString());

            PushPlugin.sendEvent( json );
        }
        catch(JSONException e) {
            // No message to the user is sent, JSON failed
            Log.e(LOG_TAG, "onRegistered: JSON exception");
        }
    }

    @Override
    public void onUnregistered(Context context, String regId) {
        Log.d(LOG_TAG, "onUnregistered - regId: " + regId);
    }

    @Override
    protected void onMessage(Context context, Intent intent) {
        Log.d(LOG_TAG, "onMessage - context: " + context);

        // Extract the payload from the message
        Bundle extras = intent.getExtras();
        if (extras != null) {
            // if we are in the foreground, just surface the payload, else post it to the statusbar
            if (PushPlugin.isInForeground() || "true".equals(getString(extras, SILENT))) {
                extras.putBoolean(FOREGROUND, PushPlugin.isInForeground());
                PushPlugin.sendExtras(extras);
            }
            else {
                extras.putBoolean(FOREGROUND, false);

                // Send a notification if there is a message
                String message = this.getMessageText(extras);
                String title = getString(extras, TITLE, "");
                if ((message != null && message.length() != 0) ||
                        (title != null && title.length() != 0)) {
                    createNotification(context, extras);
                }
            }
        }
    }

    public void createNotification(Context context, Bundle extras) {
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String appName = getAppName(this);
        String packageName = context.getPackageName();
        Resources resources = context.getResources();

        int notId = parseInt(NOT_ID, extras);
        Intent notificationIntent = new Intent(this, PushHandlerActivity.class);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        notificationIntent.putExtra(PUSH_BUNDLE, extras);
        notificationIntent.putExtra(NOT_ID, notId);

        int requestCode = new Random().nextInt();
        PendingIntent contentIntent = PendingIntent.getActivity(this, requestCode, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(context)
                        .setWhen(System.currentTimeMillis())
                        .setContentTitle(getString(extras, TITLE))
                        .setTicker(getString(extras, TITLE))
                        .setContentIntent(contentIntent)
                        .setAutoCancel(true);

        SharedPreferences prefs = context.getSharedPreferences(PushPlugin.COM_ADOBE_PHONEGAP_PUSH, Context.MODE_PRIVATE);
        String localIcon = prefs.getString(ICON, null);
        String localIconColor = prefs.getString(ICON_COLOR, null);
        boolean soundOption = prefs.getBoolean(SOUND, true);
        boolean vibrateOption = prefs.getBoolean(VIBRATE, true);
        Log.d(LOG_TAG, "stored icon=" + localIcon);
        Log.d(LOG_TAG, "stored iconColor=" + localIconColor);
        Log.d(LOG_TAG, "stored sound=" + soundOption);
        Log.d(LOG_TAG, "stored vibrate=" + vibrateOption);

        ArrayList messageList = messageMap.get(notId);

        summarize = "true".equals(getString(extras, SUMMARIZE));
        shouldSummarize = summarize && messageList != null && messageList.size() > 0;

        /*
         * Notification Vibration
         */

        setNotificationVibration(extras, vibrateOption, mBuilder);

        /*
         * Notification Icon Color
         *
         * Sets the small-icon background color of the notification.
         * To use, add the `iconColor` key to plugin android options
         *
         */
        setNotificationIconColor(getString(extras, ICON_COLOR), mBuilder, localIconColor);

        /*
         * Notification Icon
         *
         * Sets the small-icon of the notification.
         *
         * - checks the plugin options for `icon` key
         * - if none, uses the application icon
         *
         * The icon value must be a string that maps to a drawable resource.
         * If no resource is found, falls
         *
         */
        String iconUri = getString(extras, ICON);
        setNotificationSmallIcon(context, iconUri, packageName, resources, mBuilder, localIcon);

        /*
         * Notification Large-Icon
         *
         * Sets the large-icon of the notification
         *
         * - checks the gcm data for the `image` key
         * - checks to see if remote image, loads it.
         * - checks to see if assets image, Loads It.
         * - checks to see if resource image, LOADS IT!
         * - if none, we don't set the large icon
         *
         */
        if (!shouldSummarize) {
            String largeIconUri = getString(extras, IMAGE); // from gcm
            boolean roundAsCircle = "true".equals(getString(extras, ICON_CIRCLE));
            setNotificationLargeIcon(largeIconUri, roundAsCircle, packageName, resources, mBuilder);
        }

        /*
         * Notification Sound
         */
        if (soundOption) {
            setNotificationSound(context, extras, mBuilder);
        }

        /*
         *  LED Notification
         */
        setNotificationLedColor(extras, mBuilder);

        /*
         *  Priority Notification
         */
        setNotificationPriority(extras, mBuilder);

        /*
         * Notification message
         */
        setNotificationMessage(notId, extras, mBuilder);

        /*
         * Notification count
         */
        setNotificationCount(extras, mBuilder);

        /*
         * Notication add actions
         */
        createActions(extras, mBuilder, resources, packageName);

        mNotificationManager.notify(appName, notId, mBuilder.build());
    }

    private void createActions(Bundle extras, NotificationCompat.Builder mBuilder, Resources resources, String packageName) {
        Log.d(LOG_TAG, "create actions");
        String actions = getString(extras, ACTIONS);
        if (actions != null) {
            try {
                JSONArray actionsArray = new JSONArray(actions);
                for (int i=0; i < actionsArray.length(); i++) {
                    Log.d(LOG_TAG, "adding action");
                    JSONObject action = actionsArray.getJSONObject(i);
                    Log.d(LOG_TAG, "adding callback = " + action.getString(CALLBACK));
                    Intent intent = new Intent(this, PushHandlerActivity.class);
                    intent.putExtra(CALLBACK, action.getString(CALLBACK));
                    intent.putExtra(PUSH_BUNDLE, extras);
                    PendingIntent pIntent = PendingIntent.getActivity(this, i, intent, PendingIntent.FLAG_UPDATE_CURRENT);

                    mBuilder.addAction(resources.getIdentifier(action.getString(ICON), DRAWABLE, packageName),
                            action.getString(TITLE), pIntent);
                }
            } catch(JSONException e) {
                // nope
            }
        }
    }

    private void setNotificationCount(Bundle extras, NotificationCompat.Builder mBuilder) {
        String msgcnt = getString(extras, MSGCNT);
        if (msgcnt == null) {
            msgcnt = getString(extras, BADGE);
        }
        if (msgcnt != null) {
            mBuilder.setNumber(Integer.parseInt(msgcnt));
        }
    }

    private void setNotificationVibration(Bundle extras, Boolean vibrateOption, NotificationCompat.Builder mBuilder) {
        String vibrationPattern = getString(extras, VIBRATION_PATTERN);
        if (vibrationPattern != null) {
            String[] items = vibrationPattern.replaceAll("\\[", "").replaceAll("\\]", "").split(",");
            long[] results = new long[items.length];
            for (int i = 0; i < items.length; i++) {
                try {
                    results[i] = Long.parseLong(items[i]);
                } catch (NumberFormatException nfe) {}
            }
            mBuilder.setVibrate(results);
        } else {
            if (vibrateOption) {
                mBuilder.setDefaults(Notification.DEFAULT_VIBRATE);
            }
        }
    }

    private void setNotificationMessage(int notId, Bundle extras, NotificationCompat.Builder mBuilder) {
        String message = getMessageText(extras);
        String title = getString(extras, TITLE, "");

        String style = getString(extras, STYLE, STYLE_TEXT);

        if (summarize) {
            String defaultSummaryMessage = "".equals(title) ? message : (title + ": " + message);
            setNotification(notId, getString(extras, SUMMARY_MESSAGE, defaultSummaryMessage));
        }

        ArrayList<String> messageList = messageMap.get(notId);
        Integer sizeList = messageList == null ? 0 : messageList.size();

        if (shouldSummarize) {
            String summaryTitle = getString(extras, SUMMARY_TITLE, getAppName(this));

            String stacking = getString(extras, SUMMARY_TEXT, "");
            stacking = stacking.replace("%n%", sizeList.toString());

            NotificationCompat.InboxStyle notificationInbox = new NotificationCompat.InboxStyle()
                    .setBigContentTitle(summaryTitle);

            if (!"".equals(stacking)) {
                notificationInbox.setSummaryText(stacking);
                mBuilder.setContentText(stacking);
            }

            for (int i = messageList.size() - 1; i >= 0; i--) {
                notificationInbox.addLine(Html.fromHtml(messageList.get(i)));
            }

            mBuilder.setStyle(notificationInbox).setContentTitle(summaryTitle);
        } else {

            if (STYLE_INBOX.equals(style)) {
                setNotification(notId, message);

                mBuilder.setContentText(message);

                if (sizeList > 1) {
                    String sizeListMessage = sizeList.toString();
                    String stacking = sizeList + " more";
                    if (getString(extras, SUMMARY_TEXT) != null) {
                        stacking = getString(extras, SUMMARY_TEXT);
                        stacking = stacking.replace("%n%", sizeListMessage);
                    }
                    NotificationCompat.InboxStyle notificationInbox = new NotificationCompat.InboxStyle()
                            .setBigContentTitle(getString(extras, TITLE))
                            .setSummaryText(stacking);

                    for (int i = messageList.size() - 1; i >= 0; i--) {
                        notificationInbox.addLine(Html.fromHtml(messageList.get(i)));
                    }

                    mBuilder.setStyle(notificationInbox);
                } else {
                    NotificationCompat.BigTextStyle bigText = new NotificationCompat.BigTextStyle();
                    if (message != null) {
                        bigText.bigText(message);
                        bigText.setBigContentTitle(getString(extras, TITLE));
                        mBuilder.setStyle(bigText);
                    }
                }
            } else if (STYLE_PICTURE.equals(style)) {
                if (!summarize) {
                    setNotification(notId, "");
                }

                NotificationCompat.BigPictureStyle bigPicture = new NotificationCompat.BigPictureStyle();
                bigPicture.bigPicture(getBitmapFromURL(getString(extras, PICTURE)));
                bigPicture.setBigContentTitle(title);
                bigPicture.setSummaryText(message);

                mBuilder.setContentTitle(title);
                mBuilder.setContentText(message);

                mBuilder.setStyle(bigPicture);
            } else {
                if (!summarize) {
                    setNotification(notId, "");
                }

                NotificationCompat.BigTextStyle bigText = new NotificationCompat.BigTextStyle();

                if (message != null) {
                    mBuilder.setContentText(Html.fromHtml(message));

                    bigText.bigText(message);
                    bigText.setBigContentTitle(title);

                    String summaryText = getString(extras, SUMMARY_TEXT);
                    if (summaryText != null) {
                        bigText.setSummaryText(summaryText);
                    }

                    mBuilder.setStyle(bigText);
                }
                /*
                else {
                    mBuilder.setContentText("<missing message content>");
                }
                */
            }
        }
    }

    private String getString(Bundle extras,String key) {
        String message = extras.getString(key);
        if (message == null) {
            message = extras.getString(GCM_NOTIFICATION+"."+key);
        }
        return message;
    }

    private String getString(Bundle extras,String key, String defaultString) {
        String message = extras.getString(key);
        if (message == null) {
            message = extras.getString(GCM_NOTIFICATION+"."+key, defaultString);
        }
        return message;
    }

    private String getMessageText(Bundle extras) {
        String message = getString(extras, MESSAGE);
        if (message == null) {
            message = getString(extras, BODY);
        }
        return message;
    }

    private void setNotificationSound(Context context, Bundle extras, NotificationCompat.Builder mBuilder) {
        String soundname = getString(extras, SOUNDNAME);
        if (soundname == null) {
            soundname = getString(extras, SOUND);
        }
        if (soundname != null) {
            Uri sound = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE
                    + "://" + context.getPackageName() + "/raw/" + soundname);
            Log.d(LOG_TAG, sound.toString());
            mBuilder.setSound(sound);
        } else {
            mBuilder.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI);
        }
    }

    private void setNotificationLedColor(Bundle extras, NotificationCompat.Builder mBuilder) {
        String ledColor = getString(extras, LED_COLOR);
        if (ledColor != null) {
            // Converts parse Int Array from ledColor
            String[] items = ledColor.replaceAll("\\[", "").replaceAll("\\]", "").split(",");
            int[] results = new int[items.length];
            for (int i = 0; i < items.length; i++) {
                try {
                    results[i] = Integer.parseInt(items[i]);
                } catch (NumberFormatException nfe) {}
            }
            if (results.length == 4) {
                mBuilder.setLights(Color.argb(results[0], results[1], results[2], results[3]), 500, 500);
            } else {
                Log.e(LOG_TAG, "ledColor parameter must be an array of length == 4 (ARGB)");
            }
        }
    }

    private void setNotificationPriority(Bundle extras, NotificationCompat.Builder mBuilder) {
        String priorityStr = getString(extras, PRIORITY);
        if (priorityStr != null) {
            try {
                Integer priority = Integer.parseInt(priorityStr);
                if (priority >= NotificationCompat.PRIORITY_MIN && priority <= NotificationCompat.PRIORITY_MAX) {
                    mBuilder.setPriority(priority);
                } else {
                    Log.e(LOG_TAG, "Priority parameter must be between -2 and 2");
                }
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
        }
    }

    private void setNotificationLargeIcon(String imageUri, boolean roundAsCircle, String packageName, Resources resources, NotificationCompat.Builder mBuilder) {
        if (imageUri != null) {
            if (imageUri.startsWith("http://") || imageUri.startsWith("https://")) {
                Bitmap bitmap = getBitmapFromURL(imageUri);
                bitmap = formatLargeIcon(bitmap, roundAsCircle);
                mBuilder.setLargeIcon(bitmap);
                Log.d(LOG_TAG, "using remote large-icon from gcm");
            } else {
                AssetManager assetManager = getAssets();
                InputStream istr;
                try {
                    istr = assetManager.open(imageUri);
                    Bitmap bitmap = BitmapFactory.decodeStream(istr);
                    mBuilder.setLargeIcon(bitmap);
                    Log.d(LOG_TAG, "using assets large-icon from gcm");
                } catch (IOException e) {
                    int largeIconId = 0;
                    largeIconId = resources.getIdentifier(imageUri, DRAWABLE, packageName);
                    if (largeIconId != 0) {
                        Bitmap largeIconBitmap = BitmapFactory.decodeResource(resources, largeIconId);
                        mBuilder.setLargeIcon(largeIconBitmap);
                        Log.d(LOG_TAG, "using resources large-icon from gcm");
                    } else {
                        Log.d(LOG_TAG, "Not setting large icon");
                    }
                }
            }
        }
    }

    private Bitmap formatLargeIcon(Bitmap bitmap, boolean circle) {
        bitmap = resizeBitmap(bitmap);
        Bitmap output = Bitmap.createBitmap(bitmap.getWidth(),
                bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        final int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
        final Paint paint = new Paint();

        int left = 0;
        int top = 0;
        int right = size;
        int bottom = size;

        if (bitmap.getWidth() > bitmap.getHeight()) {
            left = bitmap.getWidth() / 2 - size / 2;
            right = left + size;
        } else {
            top = bitmap.getHeight() / 2 - size / 2;
            bottom = top + size;
        }

        final Rect rect = new Rect(left, top, right, bottom);

        paint.setAntiAlias(true);
        canvas.drawARGB(0, 0, 0, 0);

        if (circle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            canvas.drawCircle(bitmap.getWidth() / 2, bitmap.getHeight() / 2, size / 2, paint);
        } else {
            canvas.drawRect(rect, paint);
        }

        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(bitmap, rect, rect, paint);

        return output;
    }

    private Bitmap resizeBitmap(Bitmap bmp) {
        final int minWidth = 96;
        final int minHeight = 96;
        int width = bmp.getWidth();
        int height = bmp.getHeight();

        if (width > height) {
            int ratio = height / minHeight;
            height = minHeight;
            width = width / ratio;
        } else if (height > width) {
            int ratio = width / minWidth;
            width = minWidth;
            height = height / ratio;
        } else {
            height = minHeight;
            width = minWidth;
        }

        return Bitmap.createScaledBitmap(bmp, width, height, true);
    }

    private void setNotificationSmallIcon(Context context, String iconUri, String packageName, Resources resources, NotificationCompat.Builder mBuilder, String localIcon) {
        int iconId = 0;

        if (iconUri != null) {
            iconId = resources.getIdentifier(iconUri, DRAWABLE, packageName);
            Log.d(LOG_TAG, "using icon from plugin options");
        }
        else if (localIcon != null) {
            iconId = resources.getIdentifier(localIcon, DRAWABLE, packageName);
            Log.d(LOG_TAG, "using icon from plugin options");
        }
        if (iconId == 0) {
            Log.d(LOG_TAG, "no icon resource found - using application icon");
            iconId = context.getApplicationInfo().icon;
        }
        mBuilder.setSmallIcon(iconId);
    }

    private void setNotificationIconColor(String color, NotificationCompat.Builder mBuilder, String localIconColor) {
        int iconColor = 0;
        if (color != null) {
            try {
                iconColor = Color.parseColor(color);
            } catch (IllegalArgumentException e) {
                Log.e(LOG_TAG, "couldn't parse color from android options");
            }
        }
        else if (localIconColor != null) {
            try {
                iconColor = Color.parseColor(localIconColor);
            } catch (IllegalArgumentException e) {
                Log.e(LOG_TAG, "couldn't parse color from android options");
            }
        }
        if (iconColor != 0) {
            mBuilder.setColor(iconColor);
        }
    }

    public Bitmap getBitmapFromURL(String strURL) {
        try {
            URL url = new URL(strURL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();
            InputStream input = connection.getInputStream();
            return BitmapFactory.decodeStream(input);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String getAppName(Context context) {
        CharSequence appName =  context.getPackageManager().getApplicationLabel(context.getApplicationInfo());
        return (String)appName;
    }

    @Override
    public void onError(Context context, String errorId) {
        Log.e(LOG_TAG, "onError - errorId: " + errorId);
        // if we are in the foreground, just send the error
        if (PushPlugin.isInForeground()) {
            PushPlugin.sendError(errorId);
        }
    }

    private int parseInt(String value, Bundle extras) {
        int retval = 0;

        try {
            retval = Integer.parseInt(getString(extras, value));
        }
        catch(NumberFormatException e) {
            Log.e(LOG_TAG, "Number format exception - Error parsing " + value + ": " + e.getMessage());
        }
        catch(Exception e) {
            Log.e(LOG_TAG, "Number format exception - Error parsing " + value + ": " + e.getMessage());
        }

        return retval;
    }
}
