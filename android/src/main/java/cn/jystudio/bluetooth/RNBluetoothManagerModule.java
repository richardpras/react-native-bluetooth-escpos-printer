package cn.jystudio.bluetooth;


import android.app.Activity;
import android.os.ParcelUuid;
import android.os.Build;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.util.Log;
import android.widget.Toast;

import com.facebook.react.bridge.*;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.annotation.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
/**
 * Created by januslo on 2018/9/22.
 */
public class RNBluetoothManagerModule extends ReactContextBaseJavaModule
        implements ActivityEventListener, BluetoothServiceStateObserver {

    private static final String TAG = "BluetoothManager";
    private final ReactApplicationContext reactContext;
    public static final String EVENT_DEVICE_ALREADY_PAIRED = "EVENT_DEVICE_ALREADY_PAIRED";
    public static final String EVENT_DEVICE_FOUND = "EVENT_DEVICE_FOUND";
    public static final String EVENT_DEVICE_DISCOVER_DONE = "EVENT_DEVICE_DISCOVER_DONE";
    public static final String EVENT_CONNECTION_LOST = "EVENT_CONNECTION_LOST";
    public static final String EVENT_UNABLE_CONNECT = "EVENT_UNABLE_CONNECT";
    public static final String EVENT_CONNECTED = "EVENT_CONNECTED";
    public static final String EVENT_BLUETOOTH_NOT_SUPPORT = "EVENT_BLUETOOTH_NOT_SUPPORT";


    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE = 1;
    private static final int REQUEST_ENABLE_BT = 2;

    public static final int MESSAGE_STATE_CHANGE = BluetoothService.MESSAGE_STATE_CHANGE;
    public static final int MESSAGE_READ = BluetoothService.MESSAGE_READ;
    public static final int MESSAGE_WRITE = BluetoothService.MESSAGE_WRITE;
    public static final int MESSAGE_DEVICE_NAME = BluetoothService.MESSAGE_DEVICE_NAME;

    public static final int MESSAGE_CONNECTION_LOST = BluetoothService.MESSAGE_CONNECTION_LOST;
    public static final int MESSAGE_UNABLE_CONNECT = BluetoothService.MESSAGE_UNABLE_CONNECT;
    public static final String DEVICE_NAME = BluetoothService.DEVICE_NAME;
    public static final String TOAST = BluetoothService.TOAST;

    // Return Intent extra
    public static String EXTRA_DEVICE_ADDRESS = "device_address";

    private static final Map<String, Promise> promiseMap = Collections.synchronizedMap(new HashMap<String, Promise>());
    private static final String PROMISE_ENABLE_BT = "ENABLE_BT";
    private static final String PROMISE_SCAN = "SCAN";
    private static final String PROMISE_CONNECT = "CONNECT";
    
    private static List<Vendor> vendorList = new ArrayList<>();

    private JSONArray pairedDeivce = new JSONArray();
    private JSONArray foundDevice = new JSONArray();
    // Name of the connected device
    private String mConnectedDeviceName = null;
    // Local Bluetooth adapter
    private BluetoothAdapter mBluetoothAdapter = null;
    // Member object for the services
    private BluetoothService mService = null;

    // Class to hold the vendor information from the JSON
    private static class Vendor {
        String macPrefix;
        String vendorName;

        Vendor(String macPrefix, String vendorName) {
            this.macPrefix = macPrefix;
            this.vendorName = vendorName;
        }
    }

    public RNBluetoothManagerModule(ReactApplicationContext reactContext, BluetoothService bluetoothService) {
        super(reactContext);
        this.reactContext = reactContext;
        this.reactContext.addActivityEventListener(this);
        this.mService = bluetoothService;
        this.mService.addStateObserver(this);
        if (vendorList.isEmpty()) {
            loadVendorData(reactContext);  // Load vendor data on first use
        }
        // Register for broadcasts when a device is discovered
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        this.reactContext.registerReceiver(discoverReceiver, filter);
    }

    @Override
    public
    @Nullable
    Map<String, Object> getConstants() {
        Map<String, Object> constants = new HashMap<>();
        constants.put(EVENT_DEVICE_ALREADY_PAIRED, EVENT_DEVICE_ALREADY_PAIRED);
        constants.put(EVENT_DEVICE_DISCOVER_DONE, EVENT_DEVICE_DISCOVER_DONE);
        constants.put(EVENT_DEVICE_FOUND, EVENT_DEVICE_FOUND);
        constants.put(EVENT_CONNECTION_LOST, EVENT_CONNECTION_LOST);
        constants.put(EVENT_UNABLE_CONNECT, EVENT_UNABLE_CONNECT);
        constants.put(EVENT_CONNECTED, EVENT_CONNECTED);
        constants.put(EVENT_BLUETOOTH_NOT_SUPPORT, EVENT_BLUETOOTH_NOT_SUPPORT);
        constants.put(DEVICE_NAME, DEVICE_NAME);
        constants.put(EVENT_BLUETOOTH_NOT_SUPPORT, EVENT_BLUETOOTH_NOT_SUPPORT);
        return constants;
    }

    private BluetoothAdapter getBluetoothAdapter(){
        if(mBluetoothAdapter == null){
            // Get local Bluetooth adapter
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }
        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            emitRNEvent(EVENT_BLUETOOTH_NOT_SUPPORT,  Arguments.createMap());
        }

        return mBluetoothAdapter;
    }


    @ReactMethod
    public void enableBluetooth(final Promise promise) {
        BluetoothAdapter adapter = this.getBluetoothAdapter();
        if(adapter == null){
            promise.reject(EVENT_BLUETOOTH_NOT_SUPPORT);
        }else if (!adapter.isEnabled()) {
            // If Bluetooth is not on, request that it be enabled.
            // setupChat() will then be called during onActivityResult
            Intent enableIntent = new Intent(
                    BluetoothAdapter.ACTION_REQUEST_ENABLE);
            promiseMap.put(PROMISE_ENABLE_BT, promise);
            this.reactContext.startActivityForResult(enableIntent, REQUEST_ENABLE_BT, Bundle.EMPTY);
        } else {
            WritableArray pairedDeivce =Arguments.createArray();
            Set<BluetoothDevice> boundDevices = adapter.getBondedDevices();
            for (BluetoothDevice d : boundDevices) {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("name", d.getName());
                    obj.put("address", d.getAddress());
                    obj.put("type", identifyDeviceType(d));
                    obj.put("manufacturer", getManufacturerFromMacAddress(d));
                    pairedDeivce.pushString(obj.toString());
                } catch (Exception e) {
                    //ignore.
                }
            }
            Log.d(TAG, "ble Enabled");
            promise.resolve(pairedDeivce);
        }
    }

    @ReactMethod
    public void disableBluetooth(final Promise promise) {
        BluetoothAdapter adapter = this.getBluetoothAdapter();
        if(adapter == null){
            promise.resolve(true);
        }else {
            if (mService != null && mService.getState() != BluetoothService.STATE_NONE) {
                mService.stop();
            }
            promise.resolve(!adapter.isEnabled() || adapter.disable());
        }
    }

    @ReactMethod
    public void isBluetoothEnabled(final Promise promise) {
        BluetoothAdapter adapter = this.getBluetoothAdapter();
        promise.resolve(adapter!=null && adapter.isEnabled());
    }

    @ReactMethod
    public void scanDevices(final Promise promise) {
        BluetoothAdapter adapter = this.getBluetoothAdapter();
        if(adapter == null){
            promise.reject(EVENT_BLUETOOTH_NOT_SUPPORT);
        }else {
            cancelDisCovery();
            // Check Android version
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // For Android 12+ (API level 31 and above), check for Bluetooth permissions
                int permissionCheckedScan = ContextCompat.checkSelfPermission(reactContext, android.Manifest.permission.BLUETOOTH_SCAN);
                int permissionCheckedConnect = ContextCompat.checkSelfPermission(reactContext, android.Manifest.permission.BLUETOOTH_CONNECT);
                int permissionCheckedLocation = ContextCompat.checkSelfPermission(reactContext, android.Manifest.permission.ACCESS_FINE_LOCATION);

                if (permissionCheckedScan == PackageManager.PERMISSION_DENIED ||
                    permissionCheckedConnect == PackageManager.PERMISSION_DENIED ||
                    permissionCheckedLocation == PackageManager.PERMISSION_DENIED) {

                    // Request necessary permissions
                    ActivityCompat.requestPermissions(reactContext.getCurrentActivity(),
                            new String[]{
                                    android.Manifest.permission.BLUETOOTH_SCAN,
                                    android.Manifest.permission.BLUETOOTH_CONNECT,
                                    android.Manifest.permission.ACCESS_FINE_LOCATION
                            }, 1);
                }
            } else {
                // For Android versions below 12, only need ACCESS_FINE_LOCATION permission
                int permissionCheckedLocation = ContextCompat.checkSelfPermission(reactContext, android.Manifest.permission.ACCESS_FINE_LOCATION);

                if (permissionCheckedLocation == PackageManager.PERMISSION_DENIED) {
                    // Request location permission
                    ActivityCompat.requestPermissions(reactContext.getCurrentActivity(),
                            new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 1);
                }
            }

            pairedDeivce = new JSONArray();
            foundDevice = new JSONArray();
            Set<BluetoothDevice> boundDevices = adapter.getBondedDevices();
            for (BluetoothDevice d : boundDevices) {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("name", d.getName());
                    obj.put("address", d.getAddress());
                    obj.put("type", identifyDeviceType(d));
                    obj.put("manufacturer", getManufacturerFromMacAddress(d));
                    pairedDeivce.put(obj);
                } catch (Exception e) {
                    //ignore.
                }
            }

            WritableMap params = Arguments.createMap();
            params.putString("devices", pairedDeivce.toString());
            emitRNEvent(EVENT_DEVICE_ALREADY_PAIRED, params);
            if (!adapter.startDiscovery()) {
                promise.reject("DISCOVER", "NOT_STARTED");
                cancelDisCovery();
            } else {
                promiseMap.put(PROMISE_SCAN, promise);
            }
        }
    }

    public static void loadVendorData(Context context) {
        try {
            InputStream inputStream = context.getAssets().open("vendor_data.json"); // Put your JSON file in the assets folder
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line);
            }

            // Parse the JSON array and populate the vendor list
            JSONArray jsonArray = new JSONArray(stringBuilder.toString());
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                String macPrefix = jsonObject.getString("macPrefix");
                String vendorName = jsonObject.getString("vendorName");

                vendorList.add(new Vendor(macPrefix, vendorName));
            }

            reader.close();
        } catch (Exception e) {
            Log.e("BluetoothUtil", "Error loading vendor data: ", e);
        }
    }
    private static String getManufacturerFromMacAddress(BluetoothDevice device) {
        String macAddress = device.getAddress();
        if (macAddress != null && macAddress.length() >= 17) {
            // Extract the MAC prefix (first 3 bytes)
            String macPrefix = macAddress.substring(0, 8).toUpperCase().replace(":", "");
            // Search for the vendor in the list of vendor data
            for (Vendor vendor : vendorList) {
                String macVendor=vendor.macPrefix.toUpperCase().replace(":", "");
                if (macPrefix.equals(macPrefix)) {
                    return vendor.vendorName; // Return the matching vendor name
                }
            }
        }
        return "Unknown Manufacturer";
    }

    private String identifyDeviceType(BluetoothDevice device) {
        String deviceType = "Unknown";
    
        // 1. First, use BluetoothClass to get the major device class
        BluetoothClass bluetoothClass = device.getBluetoothClass();
        if (bluetoothClass != null) {
            int majorDeviceClass = bluetoothClass.getMajorDeviceClass();
    
            // Check device type based on its major device class
            switch (majorDeviceClass) {
                case BluetoothClass.Device.Major.AUDIO_VIDEO:
                    deviceType = "Audio Device";// (Headset, Speaker, etc.)
                    break;
                case BluetoothClass.Device.Major.PHONE:
                    deviceType = "Phone";
                    break;
                case BluetoothClass.Device.Major.COMPUTER:
                    deviceType = "Computer";
                    break;
                case BluetoothClass.Device.Major.HEALTH:
                    deviceType = "Health Device";
                    break;
                case BluetoothClass.Device.Major.MISC:
                    deviceType = "MISC";
                    break;
                case BluetoothClass.Device.Major.NETWORKING:
                    deviceType = "NETWORKING";
                    break;
                case BluetoothClass.Device.Major.TOY:
                    deviceType = "TOY";
                    break;
                case BluetoothClass.Device.Major.IMAGING:
                    deviceType = "Imaging Device";// (Printer, Camera, etc.)
                    break;
                case BluetoothClass.Device.Major.PERIPHERAL:
                    deviceType = "Peripheral Device";// (Keyboard, Mouse, etc.)
                    break;
                case BluetoothClass.Device.Major.UNCATEGORIZED:
                    deviceType = "UNCATEGORIZED";
                    break;
                case BluetoothClass.Device.Major.WEARABLE:
                    deviceType = "Wearable Device";
                    break;
                default:
                    deviceType = "Unknown";
                    break;
            }
        }
    
        // 2. If the device is still "Unknown", try to identify using UUIDs
        if (deviceType.equals("Unknown")) {
            // Get the UUIDs advertised by the device
            ParcelUuid[] uuids = device.getUuids();
            if (uuids != null) {
                for (ParcelUuid uuid : uuids) {
                    String uuidString = uuid.toString();
    
                    // Check for specific UUIDs that correspond to common Bluetooth device types
                    if (uuidString.equalsIgnoreCase("0000110B-0000-1000-8000-00805F9B34FB")) {
                        // A2DP (Advanced Audio Distribution Profile) - Audio (Headset, Speaker, etc.)
                        deviceType = "Audio Device";// (Headset, Speaker, etc.)
                        break;
                    } else if (uuidString.equalsIgnoreCase("0000111E-0000-1000-8000-00805F9B34FB")) {
                        // HFP (Hands-Free Profile) - Audio (Headset, Car Hands-Free, etc.)
                        deviceType = "Audio Device";// (Hands-Free)
                        break;
                    } else if (uuidString.equalsIgnoreCase("0000180F-0000-1000-8000-00805F9B34FB")) {
                        // Battery Service - Often found in many devices (not specific to one type)
                        deviceType = "Battery Powered Device";
                        break;
                    } else if (uuidString.equalsIgnoreCase("00001812-0000-1000-8000-00805F9B34FB")) {
                        // Printer Service (0x181F) - Printer
                        deviceType = "Imaging Device";//Printer
                        break;
                    } else if (uuidString.equalsIgnoreCase("00001800-0000-1000-8000-00805F9B34FB")) {
                        // Generic Access Profile (GAP) - Used by Android Devices and other general Bluetooth devices
                        deviceType = "Generic Device";
                    } else if (uuidString.equalsIgnoreCase("00001124-0000-1000-8000-00805F9B34FB")) {
                        // HID (Human Interface Device) - Android Devices, Keyboards, Mice, etc.
                        deviceType = "Phone";
                    }
                }
            }
        }
    
        // 3. If still unknown, fallback to checking the device name
        if (deviceType.equals("Unknown") && device.getName() != null) {
            String deviceName = device.getName().toLowerCase();
    
            // Check device name patterns for specific device types
            if (deviceName.contains("printer")) {
                deviceType = "Imaging Device";
            } else if (deviceName.contains("headset") || deviceName.contains("audio")) {
                deviceType = "Audio Device";// (Headset/Speaker)
            } else if (deviceName.contains("pixel") || deviceName.contains("galaxy")) {
                deviceType = "Android Device";
            } else if (deviceName.contains("keyboard") || deviceName.contains("mouse")) {
                deviceType = "Peripheral Device";// (Keyboard/Mouse)
            }
        }
    
        return deviceType;
    }
    

    @ReactMethod
    public void connect(String address, final Promise promise) {
        BluetoothAdapter adapter = this.getBluetoothAdapter();
        if (adapter!=null && adapter.isEnabled()) {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            promiseMap.put(PROMISE_CONNECT, promise);
            mService.connect(device);
        } else {
            promise.reject("BT NOT ENABLED");
        }

    }

    @ReactMethod
    public void disconnect(String address, final Promise promise){
        BluetoothAdapter adapter = this.getBluetoothAdapter();
        if (adapter!=null && adapter.isEnabled()) {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            try {
                mService.stop();
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
            }
            promise.resolve(address);
        } else {
            promise.reject("BT NOT ENABLED");
        }

	}

    @ReactMethod
    public void unpaire(String address,final Promise promise){
        BluetoothAdapter adapter = this.getBluetoothAdapter();
        if (adapter!=null && adapter.isEnabled()) {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            this.unpairDevice(device);
            promise.resolve(address);
        } else {
            promise.reject("BT NOT ENABLED");
        }

    }


    /*
    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    // public static final int STATE_LISTEN = 1;     // now listening for incoming connections //feathure removed.
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device
*/
    @ReactMethod
    public void isDeviceConnected(final Promise promise) {

        Boolean isConnected = true;

        if (mService != null) {
            switch (mService.getState()) {
                case 0:
                    isConnected = false;
                    break;

                case 2:
                    isConnected = false;
                    break;

                case 3:
                    isConnected = true;
                    break;

                default:
                    isConnected = false;
                    break;
            }
            promise.resolve(isConnected);
        }
    }



    /* Return the address of the currently connected device */
    @ReactMethod
    public void getConnectedDeviceAddress(final Promise promise) {
        if (mService!=null){
            promise.resolve(mService.getLastConnectedDeviceAddress());
        }

    }



        private void unpairDevice(BluetoothDevice device) {
        try {
            Method m = device.getClass()
                    .getMethod("removeBond", (Class[]) null);
            m.invoke(device, (Object[]) null);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }

    private void cancelDisCovery() {
        try {
            BluetoothAdapter adapter = this.getBluetoothAdapter();
            if (adapter!=null && adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
            Log.d(TAG, "Discover canceled");
        } catch (Exception e) {
            //ignore
        }
    }


    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        BluetoothAdapter adapter = this.getBluetoothAdapter();
        Log.d(TAG, "onActivityResult " + resultCode);
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE: {
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    // Get the device MAC address
                    String address = data.getExtras().getString(
                            EXTRA_DEVICE_ADDRESS);
                    // Get the BLuetoothDevice object
                    if (adapter!=null && BluetoothAdapter.checkBluetoothAddress(address)) {
                        BluetoothDevice device = adapter
                                .getRemoteDevice(address);
                        // Attempt to connect to the device
                        mService.connect(device);
                    }
                }
                break;
            }
            case REQUEST_ENABLE_BT: {
                Promise promise = promiseMap.remove(PROMISE_ENABLE_BT);
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK && promise != null) {
                    // Bluetooth is now enabled, so set up a session
                    if(adapter!=null){
                        WritableArray pairedDeivce =Arguments.createArray();
                        Set<BluetoothDevice> boundDevices = adapter.getBondedDevices();
                        for (BluetoothDevice d : boundDevices) {
                            try {
                                JSONObject obj = new JSONObject();
                                obj.put("name", d.getName());
                                obj.put("address", d.getAddress());
                                obj.put("type", identifyDeviceType(d));
                                obj.put("manufacturer", getManufacturerFromMacAddress(d));
                                pairedDeivce.pushString(obj.toString());
                            } catch (Exception e) {
                                //ignore.
                            }
                        }
                        promise.resolve(pairedDeivce);
                    } else {
                        promise.resolve(null);
                    }

                } else {
                    // User did not enable Bluetooth or an error occured
                    Log.d(TAG, "BT not enabled");
                    if (promise != null) {
                        promise.reject("ERR", new Exception("BT NOT ENABLED"));
                    }
                }
                break;
            }
        }
    }

    @Override
    public void onNewIntent(Intent intent) {

    }

    @Override
    public String getName() {
        return "BluetoothManager";
    }


    private boolean objectFound(JSONObject obj) {
        boolean found = false;
        if (foundDevice.length() > 0) {
            for (int i = 0; i < foundDevice.length(); i++) {
                try {
                    String objAddress = obj.optString("address", "objAddress");
                    String dsAddress = ((JSONObject) foundDevice.get(i)).optString("address", "dsAddress");
                    if (objAddress.equalsIgnoreCase(dsAddress)) {
                        found = true;
                        break;
                    }
                } catch (Exception e) {
                }
            }
        }
        return found;
    }

    // The BroadcastReceiver that listens for discovered devices and
    // changes the title when discovery is finished
    private final BroadcastReceiver discoverReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "on receive:" + action);
            // When discovery finds a device
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Get the BluetoothDevice object from the Intent
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    JSONObject deviceFound = new JSONObject();
                    try {
                        deviceFound.put("name", device.getName());
                        deviceFound.put("address", device.getAddress());
                        deviceFound.put("type", identifyDeviceType(device));
                        deviceFound.put("manufacturer", getManufacturerFromMacAddress(device));
                    } catch (Exception e) {
                        //ignore
                    }
                    if (!objectFound(deviceFound)) {
                        foundDevice.put(deviceFound);
                        WritableMap params = Arguments.createMap();
                        params.putString("device", deviceFound.toString());
                        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                                .emit(EVENT_DEVICE_FOUND, params);
                    }

                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Promise promise = promiseMap.remove(PROMISE_SCAN);
                if (promise != null) {

                    JSONObject result = null;
                    try {
                        result = new JSONObject();
                        result.put("paired", pairedDeivce);
                        result.put("found", foundDevice);
                        promise.resolve(result.toString());
                    } catch (Exception e) {
                        //ignore
                    }
                    WritableMap params = Arguments.createMap();
                    params.putString("paired", pairedDeivce.toString());
                    params.putString("found", foundDevice.toString());
                    emitRNEvent(EVENT_DEVICE_DISCOVER_DONE, params);
                }
            }
        }
    };

    private void emitRNEvent(String event, @Nullable WritableMap params) {
        getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(event, params);
    }

    @Override
    public void onBluetoothServiceStateChanged(int state, Map<String, Object> bundle) {
        Log.d(TAG, "on bluetoothServiceStatChange:" + state);
        switch (state) {
            case BluetoothService.STATE_CONNECTED:
            case MESSAGE_DEVICE_NAME: {
                // save the connected device's name
                mConnectedDeviceName = (String) bundle.get(DEVICE_NAME);
                Promise p = promiseMap.remove(PROMISE_CONNECT);
                if (p == null) {
                    Log.d(TAG, "No Promise found.");
                    WritableMap params = Arguments.createMap();
                    params.putString(DEVICE_NAME, mConnectedDeviceName);
                    emitRNEvent(EVENT_CONNECTED, params);
                } else {
                    Log.d(TAG, "Promise Resolve.");
                    p.resolve(mConnectedDeviceName);
                }

                break;
            }
            case MESSAGE_CONNECTION_LOST: {
                //Connection lost should not be the connect result.
                // Promise p = promiseMap.remove(PROMISE_CONNECT);
                // if (p == null) {
                emitRNEvent(EVENT_CONNECTION_LOST, null);
                // } else {
                //   p.reject("Device connection was lost");
                //}
                break;
            }
            case MESSAGE_UNABLE_CONNECT: {     //无法连接设备
                Promise p = promiseMap.remove(PROMISE_CONNECT);
                if (p == null) {
                    emitRNEvent(EVENT_UNABLE_CONNECT, null);
                } else {
                    p.reject("Unable to connect device");
                }

                break;
            }
            default:
                break;
        }
    }
}
