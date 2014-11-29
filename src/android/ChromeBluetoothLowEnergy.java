package org.chromium;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.support.annotation.Nullable;
import android.util.Log;
import android.os.Build;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.uribeacon.scan.compat.ScanResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import static org.apache.cordova.PluginResult.Status;

@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class ChromeBluetoothLowEnergy extends CordovaPlugin {

  private static final String LOG_TAG = "ChromeBluetoothLowEnergy";
  private Map<String, ChromeBluetoothLowEnergyPeripheral> knownPeripheral = new HashMap<>();
  private CallbackContext bluetoothLowEnergyEventsCallback;

  @Override
  public boolean execute(String action, CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {
    if ("connect".equals(action)) {
      connect(args, callbackContext);
    } else if ("disconnect".equals(action)) {
      disconnect(args, callbackContext);
    } else if ("getService".equals(action)) {
      getService(args, callbackContext);
    } else if ("getServices".equals(action)) {
      getServices(args, callbackContext);
    } else if ("getCharacteristic".equals(action)) {
      getCharacteristic(args, callbackContext);
    } else if ("getCharacteristics".equals(action)) {
      getCharacteristics(args, callbackContext);
    } else if ("getIncludedServices".equals(action)) {
      getIncludedServices(args, callbackContext);
    } else if ("getDescriptor".equals(action)) {
      getDescriptor(args, callbackContext);
    } else if ("getDescriptors".equals(action)) {
      getDescriptors(args, callbackContext);
    } else if ("readCharacteristicValue".equals(action)) {
      readCharacteristicValue(args, callbackContext);
    } else if ("writeCharacteristicValue".equals(action)) {
      writeCharacteristicValue(args, callbackContext);
    } else if ("startCharacteristicNotifications".equals(action)) {
      startCharacteristicNotifications(args, callbackContext);
    } else if ("stopCharacteristicNotifications".equals(action)) {
      stopCharacteristicNotifications(args, callbackContext);
    } else if ("readDescriptorValue".equals(action)) {
      readDescriptorValue(args, callbackContext);
    } else if ("writeDescriptorValue".equals(action)) {
      writeDescriptorValue(args, callbackContext);
    } else if ("registerBluetoothLowEnergyEvents".equals(action)) {
      registerBluetoothLowEnergyEvents(callbackContext);
    } else {
      return false;
    }
    return true;
  }

  private static String getDeviceAddressFromInstanceId(String instanceId) {
    return instanceId.split("/")[0];
  }

  // Generate a unique identifier for the BluetoothGattService object, the
  // format of the string is based on the dbus's object path in Linux system.
  private static String buildServiceId(String deviceAddress, BluetoothGattService service) {
    return new StringBuilder()
        .append(deviceAddress)
        .append("/")
        .append(service.getUuid().toString())
        .append("_")
        .append(service.getInstanceId())
        .toString();
  }

  // Generate a unique identifier for the BluetoothGattCharacteristic object,
  // the format of the string is based on the dbus's object path in Linux
  // system.
  private static String buildCharacteristicId(
      String deviceAddress, BluetoothGattCharacteristic characteristic) {
    return new StringBuilder()
        .append(deviceAddress)
        .append("/")
        .append(characteristic.getService().getUuid().toString())
        .append("/")
        .append(characteristic.getUuid().toString())
        .append("_")
        .append(characteristic.getInstanceId())
        .toString();
  }

  // Generate a unique identifier for the BluetoothGattDescriptor object, the
  // format of the string is based on the dbus's object path in Linux system.
  private static String buildDescriptorId(
      String deviceAddress, BluetoothGattDescriptor descriptor) {
    return new StringBuilder()
        .append(deviceAddress)
        .append("/")
        .append(descriptor.getCharacteristic().getService().getUuid().toString())
        .append("/")
        .append(descriptor.getCharacteristic().getUuid().toString())
        .append("/")
        .append(descriptor.getUuid().toString())
        .toString();
  }

  private static JSONObject buildServiceInfo(String deviceAddress, BluetoothGattService service)
      throws JSONException {
    JSONObject info = new JSONObject();
    info.put("uuid", service.getUuid().toString());
    info.put("deviceAddress", deviceAddress);
    info.put("instanceId", buildServiceId(deviceAddress, service));
    info.put("isPrimary", service.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY);
    return info;
  }

  private static JSONArray getPropertyStrings(int properties) throws JSONException {

    JSONArray propertyStrings = new JSONArray();

    if ((BluetoothGattCharacteristic.PROPERTY_BROADCAST & properties) != 0) {
      propertyStrings.put("broadcast");
    }

    if ((BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS & properties) != 0) {
      propertyStrings.put("extendedProperties");
    }

    if ((BluetoothGattCharacteristic.PROPERTY_INDICATE & properties) != 0) {
      propertyStrings.put("indicate");
    }

    if ((BluetoothGattCharacteristic.PROPERTY_NOTIFY & properties) != 0) {
      propertyStrings.put("notify");
    }

    if ((BluetoothGattCharacteristic.PROPERTY_READ & properties) != 0) {
      propertyStrings.put("read");
    }

    if ((BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE & properties) != 0) {
      propertyStrings.put("authenticatedSignedWrites");
    }

    if ((BluetoothGattCharacteristic.PROPERTY_WRITE & properties) != 0) {
      propertyStrings.put("write");
    }

    if ((BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE & properties) != 0) {
      propertyStrings.put("writeWithoutResponse");
    }

    return propertyStrings;
  }

  // Note: The returned object need to be sent in an array or an object as the
  // response of getCharacteristics()/getDescriptor()/getDescriptors(). The
  // "value" field is excluded due to the bridge lacking support for binary
  // data.
  private static JSONObject buildCharacteristicInfo(
      String deviceAddress, BluetoothGattCharacteristic characteristic) throws JSONException {
    JSONObject info = new JSONObject();
    info.put("uuid", characteristic.getUuid().toString());
    info.put("service", buildServiceInfo(deviceAddress, characteristic.getService()));
    info.put("properties", getPropertyStrings(characteristic.getProperties()));
    info.put("instanceId", buildCharacteristicId(deviceAddress, characteristic));
    return info;
  }

  private static List<PluginResult> buildCharacteristicMultipartInfo(
      String deviceAddress, BluetoothGattCharacteristic characteristic) throws JSONException {

    List<PluginResult> multipartInfo = new ArrayList<>();
    multipartInfo.add(new PluginResult(Status.OK, characteristic.getUuid().toString()));
    multipartInfo.add(new PluginResult(
        Status.OK, buildServiceInfo(deviceAddress, characteristic.getService())));
    multipartInfo.add(new PluginResult(
        Status.OK, getPropertyStrings(characteristic.getProperties())));
    multipartInfo.add(new PluginResult(
        Status.OK, buildCharacteristicId(deviceAddress, characteristic)));

    if (characteristic.getValue() != null) {
      multipartInfo.add(new PluginResult(Status.OK, characteristic.getValue()));
    }

    return multipartInfo;
  }

  // Note: The result object need to be sent in an array as the response of
  // getDescriptors(). The "value" field is excluded due to the bridge lacking
  // support for binary data.
  private static JSONObject buildDescriptorInfo(
      String deviceAddress, BluetoothGattDescriptor descriptor) throws JSONException {
    JSONObject info = new JSONObject();
    info.put("uuid", descriptor.getUuid().toString());
    info.put(
        "characteristic",
        buildCharacteristicInfo(deviceAddress, descriptor.getCharacteristic()));
    info.put("instanceId", buildDescriptorId(deviceAddress, descriptor));
    return info;
  }

  private static List<PluginResult> buildDescriptorMultipartInfo(
      String deviceAddress, BluetoothGattDescriptor descriptor) throws JSONException {

    List<PluginResult> multipartInfo = new ArrayList<>();

    multipartInfo.add(new PluginResult(Status.OK, descriptor.getUuid().toString()));
    multipartInfo.add(new PluginResult(Status.OK, buildCharacteristicInfo(
        deviceAddress, descriptor.getCharacteristic())));
    multipartInfo.add(new PluginResult(Status.OK, buildDescriptorId(deviceAddress, descriptor)));

    if (descriptor.getValue() != null) {
      multipartInfo.add(new PluginResult(Status.OK, descriptor.getValue()));
    }

    return multipartInfo;
  }

  @Nullable
  private ChromeBluetoothLowEnergyPeripheral getPeripheralByDeviceAddress(String deviceAddress) {
    ChromeBluetoothLowEnergyPeripheral peripheral = knownPeripheral.get(deviceAddress);

    if (peripheral != null)
      return peripheral;

    ChromeBluetooth bluetoothPlugin =
        (ChromeBluetooth) webView.getPluginManager().getPlugin("ChromeBluetooth");

    ScanResult bleScanResult = bluetoothPlugin.getKnownLeScanResults(deviceAddress);

    if (bleScanResult == null)
      return null;

    peripheral = new ChromeBluetoothLowEnergyPeripheral(bleScanResult);
    knownPeripheral.put(deviceAddress, peripheral);

    return peripheral;
  }

  private void connect(CordovaArgs args, final CallbackContext callbackContext) throws JSONException {

    String deviceAddress = args.getString(0);

    final ChromeBluetoothLowEnergyPeripheral peripheral = getPeripheralByDeviceAddress(deviceAddress);

    if (peripheral == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    cordova.getThreadPool().execute(new Runnable() {
        @Override
        public void run() {
          // Ensure connectGatt() is called in serial
          synchronized (ChromeBluetoothLowEnergy.this) {
            try {
              peripheral.connect(callbackContext);
            } catch (InterruptedException e) {
            }
          }
        }
      });
  }

  private void disconnect(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {
    String deviceAddress = args.getString(0);

    ChromeBluetoothLowEnergyPeripheral peripheral = getPeripheralByDeviceAddress(deviceAddress);

    if (peripheral == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    peripheral.disconnect(callbackContext);
  }

  private void getService(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {
    String serviceId = args.getString(0);
    String deviceAddress = getDeviceAddressFromInstanceId(serviceId);

    ChromeBluetoothLowEnergyPeripheral peripheral = getPeripheralByDeviceAddress(deviceAddress);

    if (peripheral == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    BluetoothGattService service = peripheral.getService(serviceId);

    if (service == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    callbackContext.sendPluginResult(new PluginResult(
        Status.OK, buildServiceInfo(deviceAddress, service)));
  }

  private void getServices(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {
    String deviceAddress = args.getString(0);

    ChromeBluetoothLowEnergyPeripheral peripheral = getPeripheralByDeviceAddress(deviceAddress);

    if (peripheral == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    Collection<BluetoothGattService> services = peripheral.getServices();

    JSONArray servicesInfo = new JSONArray();
    for (BluetoothGattService service : services) {
      servicesInfo.put(buildServiceInfo(deviceAddress, service));
    }

    callbackContext.sendPluginResult(new PluginResult(Status.OK, servicesInfo));
  }

  private void getCharacteristic(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {

    String characteristicId = args.getString(0);
    String deviceAddress = getDeviceAddressFromInstanceId(characteristicId);

    ChromeBluetoothLowEnergyPeripheral peripheral = getPeripheralByDeviceAddress(deviceAddress);

    if (peripheral == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    BluetoothGattCharacteristic characteristic = peripheral.getCharacteristic(characteristicId);

    if (characteristic == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    List<PluginResult> multipartMessage =
        buildCharacteristicMultipartInfo(deviceAddress, characteristic);

    callbackContext.sendPluginResult(new PluginResult(Status.OK, multipartMessage));
  }

  private void getCharacteristics(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {

    String serviceId = args.getString(0);
    String deviceAddress = getDeviceAddressFromInstanceId(serviceId);

    ChromeBluetoothLowEnergyPeripheral peripheral = getPeripheralByDeviceAddress(deviceAddress);

    if (peripheral == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    Collection<BluetoothGattCharacteristic> characteristics =
        peripheral.getCharacteristics(serviceId);

    JSONArray characteristicsInfo = new JSONArray();
    for (BluetoothGattCharacteristic characteristic : characteristics) {
      characteristicsInfo.put(buildCharacteristicInfo(deviceAddress, characteristic));
    }

    callbackContext.sendPluginResult(new PluginResult(Status.OK, characteristicsInfo));
  }

  private void getIncludedServices(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {
    String serviceId = args.getString(0);
    String deviceAddress = getDeviceAddressFromInstanceId(serviceId);

    ChromeBluetoothLowEnergyPeripheral peripheral = getPeripheralByDeviceAddress(deviceAddress);

    if (peripheral == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    Collection<BluetoothGattService> includedServices = peripheral.getIncludedServices(serviceId);

    JSONArray servicesInfo = new JSONArray();
    for (BluetoothGattService service : includedServices) {
      servicesInfo.put(buildServiceInfo(deviceAddress, service));
    }

    callbackContext.sendPluginResult(new PluginResult(Status.OK, servicesInfo));
  }

  private void getDescriptor(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {

    String descriptorId = args.getString(0);
    String deviceAddress = getDeviceAddressFromInstanceId(descriptorId);

    ChromeBluetoothLowEnergyPeripheral peripheral = getPeripheralByDeviceAddress(deviceAddress);

    if (peripheral == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    BluetoothGattDescriptor descriptor = peripheral.getDescriptor(descriptorId);

    if (descriptor == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    callbackContext.sendPluginResult(new PluginResult(
        Status.OK, buildDescriptorMultipartInfo(deviceAddress, descriptor)));
  }

  private void getDescriptors(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {

    String characteristicId = args.getString(0);
    String deviceAddress = getDeviceAddressFromInstanceId(characteristicId);

    ChromeBluetoothLowEnergyPeripheral peripheral = getPeripheralByDeviceAddress(deviceAddress);

    if (peripheral == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    Collection<BluetoothGattDescriptor> descriptors = peripheral.getDescriptors(characteristicId);

    JSONArray descriptorsInfo = new JSONArray();

    for (BluetoothGattDescriptor descriptor : descriptors) {
      descriptorsInfo.put(buildDescriptorInfo(deviceAddress, descriptor));
    }

    callbackContext.sendPluginResult(new PluginResult(Status.OK, descriptorsInfo));
  }

  private void readCharacteristicValue(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {

    final String characteristicId = args.getString(0);
    String deviceAddress = getDeviceAddressFromInstanceId(characteristicId);

    final ChromeBluetoothLowEnergyPeripheral peripheral = getPeripheralByDeviceAddress(deviceAddress);

    if (peripheral == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    cordova.getThreadPool().execute(new Runnable() {
        public void run() {
          peripheral.readCharacteristicValue(characteristicId, callbackContext);
        }
      });
  }

  private void writeCharacteristicValue(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {

    final String characteristicId = args.getString(0);
    String deviceAddress = getDeviceAddressFromInstanceId(characteristicId);
    final byte[] value = args.getArrayBuffer(1);

    final ChromeBluetoothLowEnergyPeripheral peripheral = getPeripheralByDeviceAddress(deviceAddress);

    if (peripheral == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    cordova.getThreadPool().execute(new Runnable() {
        public void run() {
          peripheral.writeCharacteristicValue(characteristicId, value, callbackContext);
        }
      });
  }

  private void startCharacteristicNotifications(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {

    final String characteristicId = args.getString(0);
    String deviceAddress = getDeviceAddressFromInstanceId(characteristicId);

    final ChromeBluetoothLowEnergyPeripheral peripheral = getPeripheralByDeviceAddress(deviceAddress);

    if (peripheral == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    cordova.getThreadPool().execute(new Runnable() {
        public void run() {
          peripheral.setCharacteristicNotification(characteristicId, true, callbackContext);
        }
      });
  }

  private void stopCharacteristicNotifications(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {

    final String characteristicId = args.getString(0);
    String deviceAddress = getDeviceAddressFromInstanceId(characteristicId);

    final ChromeBluetoothLowEnergyPeripheral peripheral = getPeripheralByDeviceAddress(deviceAddress);

    if (peripheral == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    cordova.getThreadPool().execute(new Runnable() {
        public void run() {
          peripheral.setCharacteristicNotification(characteristicId, false, callbackContext);
        }
      });
  }

  private void readDescriptorValue(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {
    final String descriptorId = args.getString(0);
    String deviceAddress = getDeviceAddressFromInstanceId(descriptorId);

    final ChromeBluetoothLowEnergyPeripheral peripheral = getPeripheralByDeviceAddress(deviceAddress);

    if (peripheral == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    cordova.getThreadPool().execute(new Runnable() {
        public void run() {
          peripheral.readDescriptorValue(descriptorId, callbackContext);
        }
      });
  }

  private void writeDescriptorValue(CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {

    final String descriptorId = args.getString(0);
    String deviceAddress = getDeviceAddressFromInstanceId(descriptorId);
    final byte[] value = args.getArrayBuffer(1);

    final ChromeBluetoothLowEnergyPeripheral peripheral = getPeripheralByDeviceAddress(deviceAddress);

    if (peripheral == null) {
      callbackContext.error("Invalid Argument");
      return;
    }

    cordova.getThreadPool().execute(new Runnable() {
        public void run() {
          peripheral.writeDescriptorValue(descriptorId, value, callbackContext);
        }
      });
  }

  private void registerBluetoothLowEnergyEvents(final CallbackContext callbackContext)
      throws JSONException {

    bluetoothLowEnergyEventsCallback = callbackContext;

  }

  private static PluginResult getMultipartServiceEventsResult(
      String eventType, String deviceAddress, BluetoothGattService service) throws JSONException {

    List<PluginResult> multipartMessage = new ArrayList<>();
    multipartMessage.add(new PluginResult(Status.OK, eventType));
    multipartMessage.add(new PluginResult(Status.OK, buildServiceInfo(deviceAddress, service)));
    PluginResult result = new PluginResult(Status.OK, multipartMessage);
    result.setKeepCallback(true);
    return result;
  }

  private void sendServiceAddedEvent(String deviceAddress, BluetoothGattService service) {
    try {
      bluetoothLowEnergyEventsCallback.sendPluginResult(
          getMultipartServiceEventsResult(
              "onServiceAdded", deviceAddress, service));
    } catch (JSONException e) {
    }
  }

  private void sendServiceChangedEvent(String deviceAddress, BluetoothGattService service) {
    try {
      bluetoothLowEnergyEventsCallback.sendPluginResult(
          getMultipartServiceEventsResult(
              "onServiceChanged", deviceAddress, service));
    } catch (JSONException e) {
    }
  }

  private void sendServiceRemovedEvent(String deviceAddress, BluetoothGattService service) {
    try {
      bluetoothLowEnergyEventsCallback.sendPluginResult(
          getMultipartServiceEventsResult(
              "onServiceRemoved", deviceAddress, service));
    } catch (JSONException e) {
    }
  }

  private void sendCharacteristicValueChangedEvent(
      String deviceAddress, BluetoothGattCharacteristic characteristic) {

    List<PluginResult> multipartMessage = new ArrayList<>();
    multipartMessage.add(new PluginResult(Status.OK, "onCharacteristicValueChanged"));

    try {
      multipartMessage.addAll(buildCharacteristicMultipartInfo(deviceAddress, characteristic));
      PluginResult result = new PluginResult(Status.OK, multipartMessage);
      result.setKeepCallback(true);
      bluetoothLowEnergyEventsCallback.sendPluginResult(result);
    } catch (JSONException e) {
    }
  }

  // From chrome API documentation: "This event exists mostly for convenience
  // and will always be sent after a successful call to readDescriptorValue."
  private void sendDescriptorValueChangedEvent(
      String deviceAddress, BluetoothGattDescriptor descriptor) {

    List<PluginResult> multipartMessage = new ArrayList<>();
    multipartMessage.add(new PluginResult(Status.OK, "onDescriptorValueChanged"));
    try {
      multipartMessage.addAll(buildDescriptorMultipartInfo(deviceAddress, descriptor));
      PluginResult result = new PluginResult(Status.OK, multipartMessage);
      result.setKeepCallback(true);
      bluetoothLowEnergyEventsCallback.sendPluginResult(result);
    } catch (JSONException e) {
    }
  }

  private class ChromeBluetoothLowEnergyPeripheral {

    // The UUID of remote notification config descriptor. We need to set the
    // value of this descriptor when startNotification() or stopNotification()
    // on characteristics.
    private final static String CLIENT_CHARACTERISTIC_CONFIG =
        "00002902-0000-1000-8000-00805f9b34fb";
    private final static int CONNECTION_TIMEOUT = 2000;

    private final ScanResult bleScanResult;

    private BluetoothGatt gatt;

    private Map<String, BluetoothGattService> knownServices = new HashMap<>();
    private Map<String, BluetoothGattCharacteristic> knownCharacteristics = new HashMap<>();
    private Map<String, BluetoothGattDescriptor> knownDescriptors = new HashMap<>();

    private CallbackContext connectCallback;
    private CallbackContext disconnectCallback;

    private CallbackContext gattCommandCallbackContext;

    // BluetoothGatt only allows one async command at a time; otherwise, it will
    // cancel the previous command. Using this semaphore to ensure calling
    // BluetoothGatt async method in serial. This Semaphore is not initialized
    // until a connect() is called.
    private Semaphore gattAsyncCommandSemaphore;

    ChromeBluetoothLowEnergyPeripheral(ScanResult bleScanResult) {
      this.bleScanResult = bleScanResult;
    }

    private synchronized void connectSuccess() {
      if (connectCallback != null) {
        connectCallback.success();
        connectCallback = null;
        gatt.discoverServices();
      }
    }

    private synchronized void connectTimeout() {
      if (connectCallback != null) {
        connectCallback.error("Connection timeout");
        connectCallback = null;
        close();
      }
    }

    private void close() {
      if (gatt != null) {
        gatt.close();
      }

      knownServices.clear();
      knownDescriptors.clear();
      knownCharacteristics.clear();
    }

    private boolean isConnected() {
      ChromeBluetooth bluetoothPlugin =
          (ChromeBluetooth) webView.getPluginManager().getPlugin("ChromeBluetooth");
      return bluetoothPlugin.isConnected(bleScanResult.getDevice());
    }

    void connect(CallbackContext callbackContext) throws InterruptedException {

      connectCallback = callbackContext;

      gatt = bleScanResult.getDevice().connectGatt(
          webView.getContext(), false, connectionCallback);

      // Reset semaphore here because some read, write's callbacks may not be
      // called when a connection lost. This abort all pending gatt commands.
      gattAsyncCommandSemaphore = new Semaphore(1, true);

      if (isConnected()) {
        connectSuccess();
      } else {
        Thread.sleep(CONNECTION_TIMEOUT);
        connectTimeout();
      }
    }

    void disconnect(CallbackContext callbackContext) {
      if (!isConnected()) {
        callbackContext.success();
        close();
      } else {
        disconnectCallback = callbackContext;
        gatt.disconnect();
      }
    }

    @Nullable
    BluetoothGattService getService(String serviceId) {
      return knownServices.get(serviceId);
    }

    Collection<BluetoothGattService> getServices() {
      if (gatt != null) {
        return gatt.getServices();
      } else {
        return Collections.emptyList();
      }
    }

    @Nullable
    BluetoothGattCharacteristic getCharacteristic(String characteristicId) {
      return knownCharacteristics.get(characteristicId);
    }

    Collection<BluetoothGattCharacteristic> getCharacteristics(String serviceId) {

      BluetoothGattService service = knownServices.get(serviceId);

      if (service == null) {
        return Collections.emptyList();
      }

      Collection<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();

      for (BluetoothGattCharacteristic characteristic : characteristics) {
        knownCharacteristics.put(
            buildCharacteristicId(bleScanResult.getDevice().getAddress(), characteristic),
            characteristic);
      }

      return characteristics;
    }

    Collection<BluetoothGattService> getIncludedServices(String serviceId) {

      BluetoothGattService service = knownServices.get(serviceId);

      if (service == null) {
        return Collections.emptyList();
      }

      Collection<BluetoothGattService> includedServices = service.getIncludedServices();

      for (BluetoothGattService includedService : includedServices) {
        String includedServiceId = buildServiceId(
            bleScanResult.getDevice().getAddress(),
            includedService);
        if (!knownServices.containsKey(serviceId)) {
          sendServiceAddedEvent(bleScanResult.getDevice().getAddress(), includedService);
        }
        knownServices.put(includedServiceId, includedService);
      }

      return includedServices;
    }

    @Nullable
    BluetoothGattDescriptor getDescriptor(String descriptorId) {
      return knownDescriptors.get(descriptorId);
    }

    Collection<BluetoothGattDescriptor> getDescriptors(String characteristicId) {

      BluetoothGattCharacteristic characteristic = knownCharacteristics.get(characteristicId);

      if (characteristic == null) {
        return Collections.emptyList();
      }

      Collection<BluetoothGattDescriptor> descriptors = characteristic.getDescriptors();

      for (BluetoothGattDescriptor descriptor : descriptors) {
        knownDescriptors.put(
            buildDescriptorId(bleScanResult.getDevice().getAddress(), descriptor),
            descriptor);
      }

      return descriptors;
    }

    void readCharacteristicValue(String characteristicId, CallbackContext callbackContext) {

      BluetoothGattCharacteristic characteristic = knownCharacteristics.get(characteristicId);

      if (characteristic == null || gatt == null) {
        callbackContext.error("Invalid Argument");
        return;
      }

      try {
        gattAsyncCommandSemaphore.acquire();
        gattCommandCallbackContext = callbackContext;
        if (!gatt.readCharacteristic(characteristic)) {
          gattCommandCallbackContext = null;
          gattAsyncCommandSemaphore.release();
          callbackContext.error("Failed to read characteristic value");
        }
      } catch (InterruptedException e) {
      }
    }

    void writeCharacteristicValue(
        String characteristicId, byte[] value, CallbackContext callbackContext) {

      BluetoothGattCharacteristic characteristic = knownCharacteristics.get(characteristicId);

      if (characteristic == null || gatt == null) {
        callbackContext.error("Invalid Argument");
        return;
      }

      try {
        gattAsyncCommandSemaphore.acquire();
        gattCommandCallbackContext = callbackContext;
        if (!(characteristic.setValue(value) && gatt.writeCharacteristic(characteristic))) {
          gattCommandCallbackContext = null;
          gattAsyncCommandSemaphore.release();
          callbackContext.error("Failed to write value into characteristic");
        }
      } catch (InterruptedException e) {
      }
    }

    void setCharacteristicNotification(
        String characteristicId, boolean enable, CallbackContext callbackContext) {

      BluetoothGattCharacteristic characteristic = knownCharacteristics.get(characteristicId);

      if (characteristic == null || gatt == null) {
        callbackContext.error("Invalid Argument");
        return;
      }

      // set characteristic local notification
      if (!gatt.setCharacteristicNotification(characteristic, enable)) {
        callbackContext.error("Failed to set characteristic local notification");
        return;
      }

      // set characteristic remote notification
      BluetoothGattDescriptor configDescriptor = characteristic.getDescriptor(
          UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG));

      if (configDescriptor == null) {
        callbackContext.error("Invalid Operation");
        return;
      }

      if (enable) {
        configDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
      } else {
        configDescriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
      }

      try {
        gattAsyncCommandSemaphore.acquire();
        gattCommandCallbackContext = callbackContext;
        if (!gatt.writeDescriptor(configDescriptor)) {
          gattCommandCallbackContext = null;
          gattAsyncCommandSemaphore.release();
          callbackContext.error("Failed to set characteristic remote notification");
        }
      } catch (InterruptedException e) {
      }
    }

    void readDescriptorValue(String descriptorId, CallbackContext callbackContext) {

      BluetoothGattDescriptor descriptor = knownDescriptors.get(descriptorId);

      if (descriptor == null || gatt == null) {
        callbackContext.error("Invalid Argument");
        return;
      }

      try {
        gattAsyncCommandSemaphore.acquire();
        gattCommandCallbackContext = callbackContext;
        if (!gatt.readDescriptor(descriptor)) {
          gattCommandCallbackContext = null;
          gattAsyncCommandSemaphore.release();
          callbackContext.error("Failed to read descriptor value");
        }
      } catch (InterruptedException e) {
      }
    }

    void writeDescriptorValue(String descriptorId, byte[] value, CallbackContext callbackContext) {

      BluetoothGattDescriptor descriptor = knownDescriptors.get(descriptorId);

      if (descriptor == null || gatt == null) {
        callbackContext.error("Invalid Argument");
        return;
      }

      try {
        gattAsyncCommandSemaphore.acquire();
        gattCommandCallbackContext = callbackContext;
        if (!(descriptor.setValue(value) && gatt.writeDescriptor(descriptor))) {
          gattCommandCallbackContext = null;
          gattAsyncCommandSemaphore.release();
          callbackContext.error("Failed to write value into descriptor");
        }
      } catch (InterruptedException e) {
      }
    }

    private BluetoothGattCallback connectionCallback = new BluetoothGattCallback() {
        @Override
        public void onCharacteristicChanged(
            BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
          sendCharacteristicValueChangedEvent(
              bleScanResult.getDevice().getAddress(), characteristic);
        }

        @Override
        public void onCharacteristicRead(
            BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {

          CallbackContext readCallbackContext = gattCommandCallbackContext;
          gattCommandCallbackContext = null;
          gattAsyncCommandSemaphore.release();

          String characteristicId = buildCharacteristicId(
              bleScanResult.getDevice().getAddress(), characteristic);

          if (readCallbackContext == null)
            return;

          switch (status) {
            case BluetoothGatt.GATT_SUCCESS:
              try {
                readCallbackContext.sendPluginResult(
                    new PluginResult(Status.OK, buildCharacteristicMultipartInfo(
                        bleScanResult.getDevice().getAddress(),
                        characteristic)));
              } catch (JSONException e) {
              }
              break;
            case BluetoothGatt.GATT_READ_NOT_PERMITTED:
              readCallbackContext.error("Read characteristic not permitted");
              break;
            default:
              readCallbackContext.error("Read characteristic failed");
          }
        }

        @Override
        public void onCharacteristicWrite(
            BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {

          CallbackContext writeCallbackContext = gattCommandCallbackContext;
          gattCommandCallbackContext = null;
          gattAsyncCommandSemaphore.release();

          String characteristicId = buildCharacteristicId(
              bleScanResult.getDevice().getAddress(), characteristic);

          if (writeCallbackContext == null)
            return;

          switch (status) {
            case BluetoothGatt.GATT_SUCCESS:
              try {
                writeCallbackContext.sendPluginResult(
                    new PluginResult(Status.OK, buildCharacteristicMultipartInfo(
                        bleScanResult.getDevice().getAddress(),
                        characteristic)));
              } catch (JSONException e) {
              }
              break;
            case BluetoothGatt.GATT_WRITE_NOT_PERMITTED:
              writeCallbackContext.error("Write characteristic not permitted");
              break;
            default:
              writeCallbackContext.error("Write characteristic failed");
          }
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

          Log.d(LOG_TAG, "connection state changes - state: " + newState);

          switch (newState) {
            case BluetoothProfile.STATE_CONNECTED:
              connectSuccess();
              break;
            case BluetoothProfile.STATE_DISCONNECTED:
              if (disconnectCallback != null) {
                disconnectCallback.success();
                disconnectCallback = null;
              }

              for (BluetoothGattService service : knownServices.values()) {
                sendServiceRemovedEvent(bleScanResult.getDevice().getAddress(), service);
              }

              close();
              break;
          }

          ChromeBluetooth bluetoothPlugin =
              (ChromeBluetooth) webView.getPluginManager().getPlugin("ChromeBluetooth");
          bluetoothPlugin.sendDeviceChangedEvent(bleScanResult);
        }

        @Override
        public void onDescriptorRead(
            BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {

          CallbackContext readCallbackContext = gattCommandCallbackContext;
          gattCommandCallbackContext = null;
          gattAsyncCommandSemaphore.release();

          String descriptorId = buildDescriptorId(
              bleScanResult.getDevice().getAddress(), descriptor);

          if (readCallbackContext == null)
            return;

          switch (status) {
            case BluetoothGatt.GATT_SUCCESS:
              try {
                readCallbackContext.sendPluginResult(
                    new PluginResult(Status.OK, buildDescriptorMultipartInfo(
                        bleScanResult.getDevice().getAddress(),
                        descriptor)));
              } catch (JSONException e) {
              }
              sendDescriptorValueChangedEvent(bleScanResult.getDevice().getAddress(), descriptor);
              break;
            case BluetoothGatt.GATT_READ_NOT_PERMITTED:
              readCallbackContext.error("Read descriptor not permitted");
              break;
            default:
              readCallbackContext.error("Read descriptor failed");
          }
        }

        @Override
        public void onDescriptorWrite(
            BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
          CallbackContext callbackContext = gattCommandCallbackContext;
          gattCommandCallbackContext = null;
          gattAsyncCommandSemaphore.release();

          PluginResult result = null;

          if (descriptor.getUuid().toString().equals(CLIENT_CHARACTERISTIC_CONFIG)) {
            // Set remote notification by writing into config descriptor
            String characteristicId = buildCharacteristicId(
                bleScanResult.getDevice().getAddress(),
                descriptor.getCharacteristic());
            result = new PluginResult(Status.OK);

          } else {
            // Normal descriptor write
            String descriptorId = buildDescriptorId(
                bleScanResult.getDevice().getAddress(), descriptor);

            try {
              result = new PluginResult(Status.OK, buildDescriptorMultipartInfo(
                  bleScanResult.getDevice().getAddress(),
                  descriptor));
            } catch (JSONException e) {
            }
          }

          if (callbackContext == null || result == null)
            return;

          switch (status) {
            case BluetoothGatt.GATT_SUCCESS:
              callbackContext.sendPluginResult(result);
              break;
            case BluetoothGatt.GATT_WRITE_NOT_PERMITTED:
              callbackContext.error("Write descriptor not permitted");
              break;
            default:
              callbackContext.error("Write descriptor failed");
          }
        }

        // Not Implemented: onReadRemoteRssi
        // Not Implemented: onReliableWriteComplete

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {

          if (status == BluetoothGatt.GATT_SUCCESS) {
            List<BluetoothGattService> discoveredServices = gatt.getServices();
            for (BluetoothGattService discoveredService : discoveredServices) {
              String serviceId = buildServiceId(
                  bleScanResult.getDevice().getAddress(),
                  discoveredService);
              if (knownServices.containsKey(serviceId)) {
                sendServiceChangedEvent(bleScanResult.getDevice().getAddress(), discoveredService);
              } else {
                sendServiceAddedEvent(bleScanResult.getDevice().getAddress(), discoveredService);
              }
              knownServices.put(serviceId, discoveredService);
            }
          }
        }
      };
  }
}
