package com.chariotsolutions.nfc.plugin;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentFilter.MalformedMimeTypeException;
import android.nfc.*;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.nfc.tech.TagTechnology;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcelable;
import android.util.Log;

import org.apache.cordova.api.CallbackContext;
import org.apache.cordova.api.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

public class NfcPlugin extends CordovaPlugin {
    private static final String REGISTER_MIME_TYPE = "registerMimeType";
    private static final String REGISTER_NDEF = "registerNdef";
    private static final String REGISTER_NDEF_FORMATABLE = "registerNdefFormatable";
    private static final String REGISTER_DEFAULT_TAG = "registerTag";
    private static final String CONNECT = "connect";
    private static final String CLOSE = "close";
    private static final String READ_NDEF = "readNdef";
    private static final String WRITE_NDEF = "writeNdef";
    private static final String WRITE_TAG = "writeTag";
    private static final String SHARE_TAG = "shareTag";
    private static final String UNSHARE_TAG = "unshareTag";
    private static final String INIT = "init";

    private static final String NDEF = "ndef";
    private static final String NDEF_MIME = "ndef-mime";
    private static final String NDEF_FORMATABLE = "ndef-formatable";
    private static final String TAG_DEFAULT = "tag";
    private static final String TAG_LOST = "tag-lost";

    private static final String ERROR_NO_NFC = "NO_NFC";
    private static final String ERROR_NFC_DISABLED = "NFC_DISABLED";
    
    private static final String TAG = "NfcPlugin";

    private class TagWorker {
        private TagTechnology tech;
        private Handler asyncHandler;

        TagWorker(TagTechnology tech) {
            this.tech = tech;
            HandlerThread asyncThread = new HandlerThread("PhoneGap NFC worker");
            asyncThread.start();
            this.asyncHandler = new Handler(asyncThread.getLooper());
        }

        /**
         * Closes the attached tag access object and instructs the worker
         * thread to terminate.
         * This method can be safely called from any thread.
         */
        public void close() throws IOException {
            tech.close();
            asyncHandler.getLooper().quit();
        }

        public void close(CallbackContext ctx) {
            try {
                close();
                ctx.success();
            } catch (IOException e) {
                ctx.error("Failed to close NFC tag connection: " + e.getMessage());
            }
        }

        void connect(final CallbackContext ctx) {
            asyncHandler.post(new Runnable() {
                public void run() {
                    try {
                        tech.connect();  // blocking I/O
                        ctx.success();
                    } catch (TagLostException e) {
                        handleTagLost();
                        ctx.error("Tag left the field");
                    } catch (IOException e) {
                        ctx.error("NFC tag connect failed: " + e.getMessage());
                    }
                }
            });
        }

        // Support for deprecated stateless usage of writeTag
        void connectSync() throws IOException {
            Log.d(TAG, "Blocking on NFC tag connect; consider using nfc.connect");
            tech.connect();
        }
        void closeWhenDone() {
            asyncHandler.post(new Runnable() {
                public void run() {
                    Log.d(TAG, "Closing the one-shot NFC tag connection; consider using nfc.connect");
                    try {
                        tech.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to close NFC tag connection", e);
                    }
                    asyncHandler.getLooper().quit();
                }
            });
        }

        void readNdef(final CallbackContext ctx) {
            asyncHandler.post(new Runnable() {
                public void run() {
                    if (!(tech instanceof Ndef)) {
                        ctx.error("Tag does not have a NDEF message");
                        return;
                    }
                    try {
                        Ndef ndef = (Ndef)tech;
                        NdefMessage msg = ndef.getNdefMessage();  // blocking I/O
                        ctx.success(Util.messageToJSON(msg));
                    } catch (TagLostException e) {
                        handleTagLost();
                        ctx.error("Tag left the field");
                    } catch (Exception e) {
                        ctx.error("NDEF read error: " + e.getMessage());
                    }
                }
            });
        }

        void writeNdef(final NdefMessage message, final CallbackContext ctx) {
            asyncHandler.post(new Runnable() {
                public void run() {
                    try {
                        if (tech instanceof Ndef) {
                            Ndef ndef = (Ndef)tech;
                            if (!ndef.isWritable()) {
                                throw new TagWriteException("Tag is read only");
                            }

                            int size = message.toByteArray().length;  // Could use NdefMessage.getByteArrayLength() with API level 16
                            if (ndef.getMaxSize() < size) {
                                String errorMessage = "Tag capacity is " + ndef.getMaxSize() + " bytes, message is " + size + " bytes.";
                                throw new TagWriteException(errorMessage);
                            }

                            ndef.writeNdefMessage(message);  // blocking I/O
                        } else if (tech instanceof NdefFormatable) {
                            NdefFormatable formatable = (NdefFormatable)tech;
                            formatable.format(message);  // blocking I/O
                        } else {
                            Log.wtf(TAG, "Unexpected tag technology " + tech.getClass().getName());
                            throw new TagWriteException("Internal error");
                        }
                        ctx.success();
                    } catch (TagLostException e) {
                        handleTagLost();
                        ctx.error("Tag left the field");
                    } catch (Exception e) {
                        ctx.error("NDEF write error: " + e.getMessage());
                    }
                }
            });
        }
    }

    private final List<IntentFilter> intentFilters = new ArrayList<IntentFilter>();
    private final ArrayList<String[]> techLists = new ArrayList<String[]>();

    private volatile NfcAdapter nfcAdapter = null;
    private NdefMessage p2pMessage = null;
    private PendingIntent pendingIntent = null;
    private volatile Tag detectedTag = null;
    private TagWorker tagWorker = null;

    @Override
    public boolean execute(String action, JSONArray data,
                           CallbackContext callback) throws JSONException {
        Log.d(TAG, "execute " + action);

        if (action.equalsIgnoreCase(REGISTER_MIME_TYPE)) {
            try {
                addMimeTypeFilter(data.getString(0));
            } catch (MalformedMimeTypeException e) {
                callback.error("Invalid MIME Type");
                return true;
            }
            updateNfcDispatch();

            callback.success();
            return true;
        } else if (action.equalsIgnoreCase(REGISTER_NDEF)) {
            addTechList(new String[]{Ndef.class.getName()});
            updateNfcDispatch();

            callback.success();
            return true;
        } else if (action.equalsIgnoreCase(REGISTER_NDEF_FORMATABLE)) {
            addTechList(new String[]{NdefFormatable.class.getName()});
            updateNfcDispatch();

            callback.success();
            return true;
        }  else if (action.equals(REGISTER_DEFAULT_TAG)) {
            addTagFilter();
            updateNfcDispatch();

            callback.success();
            return true;
        } else if (action.equalsIgnoreCase(CONNECT)) {
            if (tagWorker != null) {
                callback.error("Already connected");
                return true;
            }
            Tag tag = this.detectedTag;
            if (tag == null) {
                callback.error("No tag is detected");
                return true;
            }
            TagTechnology tech = Util.ndefTechForTag(tag);
            if (tech == null) {
                callback.error("Tag doesn't support NDEF");
                return true;
            }

            tagWorker = new TagWorker(tech);
            tagWorker.connect(callback);
            return true;
        } else if (action.equalsIgnoreCase(CLOSE)) {
            if (tagWorker == null) {
                callback.error("Tag is not connected");
                return true;
            }
            tagWorker.close(callback);
            tagWorker = null;
            return true;
        } else if (action.equalsIgnoreCase(READ_NDEF)) {
            if (tagWorker == null) {
                callback.error("Tag is not connected");
                return true;
            }
            tagWorker.readNdef(callback);
            return true;
        } else if (action.equalsIgnoreCase(WRITE_NDEF)) {
            if (tagWorker == null) {
                callback.error("Tag is not connected");
                return true;
            }
            NdefRecord[] records = Util.jsonToNdefRecords(data.getString(0));
            tagWorker.writeNdef(new NdefMessage(records), callback);
            return true;
        } else if (action.equalsIgnoreCase(WRITE_TAG)) {
            NdefRecord[] records = Util.jsonToNdefRecords(data.getString(0));
            writeTag(new NdefMessage(records), callback);
            return true;

        } else if (action.equalsIgnoreCase(SHARE_TAG)) {

            NdefRecord[] records = Util.jsonToNdefRecords(data.getString(0));

            startNdefPush(new NdefMessage(records));

            callback.success();
            return true;

        } else if (action.equalsIgnoreCase(UNSHARE_TAG)) {
            stopNdefPush();
            callback.success();
            return true;

        } else if (action.equalsIgnoreCase(INIT)) {
            Log.d(TAG, "Enabling plugin");

            NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(getActivity());

            if (nfcAdapter == null) {
                callback.error(ERROR_NO_NFC);
                return true;
            } else if (!nfcAdapter.isEnabled()) {
                callback.error(ERROR_NFC_DISABLED);
                return true;
            } // Note: a non-error could be NDEF_PUSH_DISABLED

            callback.success();

            // Parse the intent and fire tag events after the plugin
            // initialization is nominally complete.
            // FIXME: race conditions with nfc.add*Listener? 
            getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    detectTag(getActivity().getIntent());
                }
            });

            return true;

        }
        Log.d(TAG, "no result");
        return false;
    }

    private void createPendingIntent() {
        if (pendingIntent == null) {
            Activity activity = getActivity();
            Intent intent = new Intent(activity, activity.getClass());
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pendingIntent = PendingIntent.getActivity(activity, 0, intent, 0);
        }
    }

    private void addTechList(String[] list) {
        synchronized (this) {
            intentFilters.add(new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED));
            techLists.add(list);
        }
    }

    private void addTagFilter() {
        synchronized (this) {
            intentFilters.add(new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED));
        }
    }

    private void addMimeTypeFilter(String mimeType) throws MalformedMimeTypeException {
        IntentFilter intentFilter = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);
        intentFilter.addDataType(mimeType);
        synchronized (this) {
            intentFilters.add(intentFilter);
        }
    }

    private void updateNfcDispatch() {
        if (nfcAdapter == null)  // shortcut to avoid scheduling
            return;
        getActivity().runOnUiThread(new Runnable() {
            public void run() {
                updateNfcDispatchOnUiThread();
            }
        });
    }

    private void updateNfcDispatchOnUiThread() {
        if (nfcAdapter != null) {
            IntentFilter[] filtersArray;
            String[][] techListsArray;
            synchronized (this) {
                filtersArray = intentFilters
                        .toArray(new IntentFilter[intentFilters.size()]);
                techListsArray = techLists
                        .toArray(new String[techLists.size()][]);
            }
            nfcAdapter.enableForegroundDispatch(getActivity(),
                    pendingIntent, filtersArray, techListsArray);
        }
    }

    private void startNdefPush(final NdefMessage message) {
        if (nfcAdapter == null)  // shortcut to avoid scheduling
            return;
        getActivity().runOnUiThread(new Runnable() {
            public void run() {
                p2pMessage = message;
                if (nfcAdapter != null) {
                    nfcAdapter.enableForegroundNdefPush(getActivity(), p2pMessage);
                }
            }
        });
    }

    private void stopNdefPush() {
        if (nfcAdapter == null)  // shortcut to avoid scheduling
            return;
        getActivity().runOnUiThread(new Runnable() {
            public void run() {
                p2pMessage = null;
                if (nfcAdapter != null) {
                    nfcAdapter.disableForegroundNdefPush(getActivity());
                }
            }
        });
    }

    // NOTE: called in the UI thread
    private void detectTag(Intent intent) {
        Log.d(TAG, "detectTag " + intent);
        String action = intent.getAction();
        Log.d(TAG, "action " + action);
        if (action == null) { return; }

        int flags = intent.getFlags();
        if ((flags & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            Log.d(TAG, "Launched from history, ignoring recycled intent");
            return;
        }

        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        Parcelable[] messages = intent.getParcelableArrayExtra((NfcAdapter.EXTRA_NDEF_MESSAGES));

        synchronized (this) {
            if (tag != null) {
                this.detectedTag = tag;
            }
    
            if (action.equals(NfcAdapter.ACTION_NDEF_DISCOVERED)) {
                Ndef ndef = Ndef.get(tag);
                fireNdefEvent(NDEF_MIME, ndef, messages);
    
            } else if (action.equals(NfcAdapter.ACTION_TECH_DISCOVERED)) {
                for (String tagTech : tag.getTechList()) {
                    Log.d(TAG, tagTech);
                    if (tagTech.equals(NdefFormatable.class.getName())) {
                        fireNdefEvent(NDEF_FORMATABLE, null, null);
                    } else if (tagTech.equals(Ndef.class.getName())) { //
                        Ndef ndef = Ndef.get(tag);
                        fireNdefEvent(NDEF, ndef, messages);
                    }
                }
            } else if (action.equals(NfcAdapter.ACTION_TAG_DISCOVERED)) {
                fireTagEvent(tag);
            }
        }
    }

    // NOTE: called in the tag worker thread
    private void handleTagLost() {
        synchronized (this) {
            if (this.detectedTag == null) {
                return;
            }
            this.detectedTag = null;
            String command =
                    "var e = document.createEvent('Events');\n" +
                    "e.initEvent('" + TAG_LOST + "');\n" +
                    "document.dispatchEvent(e);";
            Log.v(TAG, command);
            this.webView.sendJavascript(command);
        }
    }

    private void fireNdefEvent(String type, Ndef ndef, Parcelable[] messages) {

        String javascriptTemplate =
            "var e = document.createEvent(''Events'');\n" +
            "e.initEvent(''{0}'');\n" +
            "e.tag = {1};\n" +
            "document.dispatchEvent(e);";

        JSONObject jsonObject = buildNdefJSON(ndef, messages);
        String tag = jsonObject.toString();

        String command = MessageFormat.format(javascriptTemplate, type, tag);
        Log.v(TAG, command);
        this.webView.sendJavascript(command);

    }

    private void fireTagEvent (Tag tag) {
        String javascriptTemplate =
            "var e = document.createEvent(''Events'');\n" +
            "e.initEvent(''{0}'');\n" +
            "e.tag = {1};\n" +
            "document.dispatchEvent(e);";

        String command = MessageFormat.format(javascriptTemplate, TAG_DEFAULT, Util.tagToJSON(tag));
        Log.v(TAG, command);
        this.webView.sendJavascript(command);
    }

    JSONObject buildNdefJSON(Ndef ndef, Parcelable[] messages) {

        JSONObject json = Util.ndefToJSON(ndef);

        // ndef is null for peer-to-peer
        // ndef and messages are null for ndef format-able
        if (ndef == null && messages != null) {

            try {

                if (messages.length > 0) {
                    NdefMessage message = (NdefMessage) messages[0];
                    json.put("ndefMessage", Util.messageToJSON(message));
                    // guessing type, would prefer a more definitive way to determine type
                    json.put("type", "NDEF Push Protocol");
                }

                if (messages.length > 1) {
                    Log.wtf(TAG, "Expected one ndefMessage but found " + messages.length);
                }

            } catch (JSONException e) {
                // shouldn't happen
                Log.e(Util.TAG, "Failed to convert ndefMessage into json", e);
            }
        }
        return json;
    }

    private void writeTag(NdefMessage message, CallbackContext ctx) {
        boolean oneShotWorker = false;
        if (tagWorker == null) {
            // Deprecated stateless usage without prior connect()

            Tag tag = this.detectedTag;
            if (tag == null) {
                ctx.error("Tag is not available");
                return;
            }

            TagTechnology tech = Util.ndefTechForTag(tag);
            if (tech == null) {
                ctx.error("Tag doesn't support NDEF");
                return;
            }

            tagWorker = new TagWorker(tech);
            try {
                tagWorker.connectSync();
            } catch (IOException e) {
                ctx.error("NFC tag connect failed: " + e.getMessage());
                tagWorker = null;
                return;
            }
            oneShotWorker = true;
        }

        tagWorker.writeNdef(message, ctx);

        if (oneShotWorker) {
            tagWorker.closeWhenDone();
            tagWorker = null;
        }
    }

    @Override
    public void onPause(boolean multitasking) {
        Log.d(TAG, "onPause");

        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(getActivity());
            nfcAdapter.disableForegroundNdefPush(getActivity());
            nfcAdapter = null;
        }

        super.onPause(multitasking);
    }

    @Override
    public void onResume(boolean multitasking) {
        Log.d(TAG, "onResume");
        super.onResume(multitasking);

        if (nfcAdapter != null) {
            Log.wtf(TAG, "NFC adapter was active on resume");
            return;
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(getActivity());

        if (nfcAdapter != null) {
            createPendingIntent();
            updateNfcDispatchOnUiThread();

            if (p2pMessage != null) {
                nfcAdapter.enableForegroundNdefPush(getActivity(), p2pMessage);
            }
        }
    }

    @Override
    public void onDestroy() {
        // Caution here: this.tagWorker may be modified by the execute thread
        TagWorker tw = this.tagWorker;
        if (tw != null) {
            Log.w(TAG, "reaping the unreleased NFC worker, nfc.close() was not called");
            try {
                tw.close();
            } catch (IOException e) {
                Log.e(TAG, "Failure on NFC release", e);
            }
        }
        super.onDestroy();
    }

    @Override
    public void onNewIntent(Intent intent) {
        Log.d(TAG, "onNewIntent " + intent);
        super.onNewIntent(intent);
        detectTag(intent);
    }

    private Activity getActivity() {
        return this.cordova.getActivity();
    }
}
