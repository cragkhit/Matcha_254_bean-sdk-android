/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Little Robots
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.punchthrough.bean.sdk;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.punchthrough.bean.sdk.internal.MessageID;
import com.punchthrough.bean.sdk.internal.battery.BatteryProfile.BatteryLevelCallback;
import com.punchthrough.bean.sdk.internal.ble.GattClient;
import com.punchthrough.bean.sdk.internal.device.DeviceProfile.DeviceInfoCallback;
import com.punchthrough.bean.sdk.internal.serial.GattSerialMessage;
import com.punchthrough.bean.sdk.internal.serial.GattSerialTransportProfile;
import com.punchthrough.bean.sdk.message.Acceleration;
import com.punchthrough.bean.sdk.message.AccelerometerRange;
import com.punchthrough.bean.sdk.message.BatteryLevel;
import com.punchthrough.bean.sdk.message.Callback;
import com.punchthrough.bean.sdk.message.DeviceInfo;
import com.punchthrough.bean.sdk.message.LedColor;
import com.punchthrough.bean.sdk.message.Message;
import com.punchthrough.bean.sdk.message.RadioConfig;
import com.punchthrough.bean.sdk.message.ScratchBank;
import com.punchthrough.bean.sdk.message.ScratchData;
import com.punchthrough.bean.sdk.message.SketchHex;
import com.punchthrough.bean.sdk.message.SketchMetadata;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import okio.Buffer;

import static com.punchthrough.bean.sdk.internal.Protocol.APP_MSG_RESPONSE_BIT;

/**
 * Interacts with the Punch Through Design Bean hardware.
 */
public class Bean implements Parcelable {

    public static final Creator<Bean> CREATOR = new Creator<Bean>() {
        @Override
        public Bean createFromParcel(Parcel source) {
            // ugly cast to fix bogus warning in Android Studio...
            BluetoothDevice device = source.readParcelable(((Object) this).getClass().getClassLoader());
            if (device == null) {
                throw new IllegalStateException("Device is null");
            }
            return new Bean(device);
        }

        @Override
        public Bean[] newArray(int size) {
            return new Bean[size];
        }
    };
    private static final String TAG = "Bean";
    private BeanListener mInternalBeanListener = new BeanListener() {
        @Override
        public void onConnected() {
            Log.w(TAG, "onConnected after disconnect from device " + getDevice().getAddress());
        }

        @Override
        public void onConnectionFailed() {
            Log.w(TAG, "onConnectionFailed after disconnect from device " + getDevice().getAddress());
        }

        @Override
        public void onDisconnected() {
        }

        @Override
        public void onSerialMessageReceived(byte[] data) {
        }

        @Override
        public void onScratchValueChanged(int bank, byte[] value) {
        }
    };
    private BeanListener mBeanListener = mInternalBeanListener;
    private final GattClient mGattClient;
    private final GattSerialTransportProfile.Listener mTransportListener;
    private final BluetoothDevice mDevice;
    private boolean mConnected;
    private HashMap<MessageID, List<Callback<?>>> mCallbacks = new HashMap<>(16);
    private Handler mHandler = new Handler(Looper.getMainLooper());

    /**
     * Create a Bean using it's {@link android.bluetooth.BluetoothDevice}
     * The bean will not be connected until {@link #connect(android.content.Context, BeanListener)} is called.
     *
     * @param device the device
     */
    public Bean(BluetoothDevice device) {
        mDevice = device;
        mTransportListener = new GattSerialTransportProfile.Listener() {
            @Override
            public void onConnected() {
                mConnected = true;
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mBeanListener.onConnected();
                    }
                });
            }

            @Override
            public void onConnectionFailed() {
                mConnected = false;
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mBeanListener.onConnectionFailed();
                    }
                });
            }

            @Override
            public void onDisconnected() {
                mCallbacks.clear();
                mConnected = false;
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mBeanListener.onDisconnected();
                    }
                });
            }

            @Override
            public void onMessageReceived(final byte[] data) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        handleMessage(data);
                    }
                });
            }

            @Override
            public void onScratchValueChanged(final int bank, final byte[] value) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mBeanListener.onScratchValueChanged(bank, value);
                    }
                });
            }
        };
        mGattClient = new GattClient();
        mGattClient.getSerialProfile().setListener(mTransportListener);
    }

    /**
     * Check if the bean is connected
     *
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return mConnected;
    }

    /**
     * Attempt to connect to the Bean
     *
     * @param context  the context used for connection
     * @param listener the bean listener
     */
    public void connect(Context context, BeanListener listener) {
        if (mConnected) {
            return;
        }
        mBeanListener = listener;
        mGattClient.connect(context, mDevice);
    }

    /**
     * Disconnect the bean
     */
    public void disconnect() {
        mBeanListener = mInternalBeanListener;
        mGattClient.disconnect();
        mConnected = false;
    }

    /**
     * Return the {@link android.bluetooth.BluetoothDevice} for this bean
     *
     * @return the device
     */
    public BluetoothDevice getDevice() {
        return mDevice;
    }

    /**
     * Request the {@link com.punchthrough.bean.sdk.message.RadioConfig}
     *
     * @param callback the callback for the result
     */
    public void readRadioConfig(Callback<RadioConfig> callback) {
        addCallback(MessageID.BT_GET_CONFIG, callback);
        sendMessageWithoutPayload(MessageID.BT_GET_CONFIG);
    }

    /**
     * Set the led values
     *
     * @param color The color being sent to the LED
     */
    public void setLed(LedColor color) {
        Buffer buffer = new Buffer();
        buffer.writeByte(color.red());
        buffer.writeByte(color.green());
        buffer.writeByte(color.blue());
        sendMessage(MessageID.CC_LED_WRITE_ALL, buffer);
    }

    /**
     * Read the led state
     *
     * @param callback the callback for the result
     */
    public void readLed(Callback<LedColor> callback) {
        addCallback(MessageID.CC_LED_READ_ALL, callback);
        sendMessageWithoutPayload(MessageID.CC_LED_READ_ALL);
    }

    /**
     * Set the advertising flag (note: does not appear to work at this time)
     *
     * @param enable true to enable, false otherwise
     */
    public void setAdvertising(boolean enable) {
        Buffer buffer = new Buffer();
        buffer.writeByte(enable ? 1 : 0);
        sendMessage(MessageID.BT_ADV_ONOFF, buffer);
    }

    /**
     * Request a temperature reading
     *
     * @param callback the callback for the result
     */
    public void readTemperature(Callback<Integer> callback) {
        addCallback(MessageID.CC_TEMP_READ, callback);
        sendMessageWithoutPayload(MessageID.CC_TEMP_READ);
    }

    /**
     * Request an acceleration sensor reading
     *
     * @param callback the callback for the result
     */
    public void readAcceleration(Callback<Acceleration> callback) {
        addCallback(MessageID.CC_ACCEL_READ, callback);
        sendMessageWithoutPayload(MessageID.CC_ACCEL_READ);
    }

    /**
     * Request the sketch metadata
     *
     * @param callback the callback for the result
     */
    public void readSketchMetaData(Callback<SketchMetadata> callback) {
        addCallback(MessageID.BL_GET_META, callback);
        sendMessageWithoutPayload(MessageID.BL_GET_META);
    }

    /**
     * Request a scratch bank data value
     *
     * @param number   the scratch bank number, must be in the range 0-4 (inclusive)
     * @param callback the callback for the result
     */
    public void readScratchData(int number, Callback<ScratchData> callback) {
        addCallback(MessageID.BT_GET_SCRATCH, callback);
        Buffer buffer = new Buffer();
        if (number < 0 || number > 5) {
            throw new IllegalArgumentException("Scratch bank must be in the range of 0-4");
        }
        buffer.writeByte((number + 1) & 0xff);
        sendMessage(MessageID.BT_GET_SCRATCH, buffer);
    }

    /**
     * Set accelerometer range.
     *
     * @param range the range in G's, must be 2, 4, 8 or 16
     */
    public void setAccelerometerRange(AccelerometerRange range) {
        Buffer buffer = new Buffer();
        buffer.writeByte(range.getRawValue());
        sendMessage(MessageID.CC_ACCEL_SET_RANGE, buffer);
    }

    /**
     * Read the accelerometer range in G's
     *
     * @param callback the callback for the result
     */
    public void readAccelerometerRange(Callback<Integer> callback) {
        addCallback(MessageID.CC_ACCEL_GET_RANGE, callback);
        sendMessageWithoutPayload(MessageID.CC_ACCEL_GET_RANGE);
    }

    /**
     * Set a scratch bank data value.
     *
     * @param bank The scratch bank being set
     * @param data The bytes to write into the scratch bank
     */
    public void setScratchData(ScratchBank bank, byte[] data) {
        ScratchData sd = ScratchData.create(bank, data);
        sendMessage(MessageID.BT_SET_SCRATCH, sd);
    }

    /**
     * Set a scratch bank data value.
     *
     * @param bank The scratch bank being set
     * @param data The string data to write into the scratch bank
     */
    public void setScratchData(ScratchBank bank, String data) {
        ScratchData sd = ScratchData.create(bank, data);
        sendMessage(MessageID.BT_SET_SCRATCH, sd);
    }

    /**
     * Set the {@link com.punchthrough.bean.sdk.message.RadioConfig}
     * <p/>
     * This is equivalent to calling
     * {@link #setRadioConfig(com.punchthrough.bean.sdk.message.RadioConfig, boolean)}
     * with true for the save parameter.
     *
     * @param config the configuration to set
     */
    public void setRadioConfig(RadioConfig config) {
        setRadioConfig(config, true);
    }

    /**
     * Set the {@link com.punchthrough.bean.sdk.message.RadioConfig}
     *
     * @param config the configuration to set
     * @param save   true to save the config in non-volatile storage, false otherwise.
     */
    public void setRadioConfig(RadioConfig config, boolean save) {
        sendMessage(save ? MessageID.BT_SET_CONFIG : MessageID.BT_SET_CONFIG_NOSAVE, config);
    }

    /**
     * Send a serial message
     *
     * @param value the message payload
     */
    public void sendSerialMessage(byte[] value) {
        Buffer buffer = new Buffer();
        buffer.write(value);
        sendMessage(MessageID.SERIAL_DATA, buffer);
    }

    /**
     * Set the pin code
     *
     * @param pin    the 6 digit pin as a number, for example <code>123456</code>
     * @param active true to enable authenticated mode, false to disable the current pin
     */
    public void setPin(int pin, boolean active) {
        Buffer buffer = new Buffer();
        buffer.writeIntLe(pin);
        buffer.writeByte(active ? 1 : 0);
        sendMessage(MessageID.BT_SET_PIN, buffer);
    }

    /**
     * Send a serial message.
     *
     * @param value the message which will be converted to UTF-8 bytes.
     */
    public void sendSerialMessage(String value) {
        Buffer buffer = new Buffer();
        try {
            buffer.write(value.getBytes("UTF-8"));
            sendMessage(MessageID.SERIAL_DATA, buffer);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Read the device information (hardware / software version)
     *
     * @param callback the callback for the result
     */
    public void readDeviceInfo(final Callback<DeviceInfo> callback) {
        mGattClient.getDeviceProfile().getDeviceInfo(new DeviceInfoCallback() {
            @Override
            public void onDeviceInfo(DeviceInfo info) {
                callback.onResult(info);
            }
        });
    }

    /**
     * Enable or disable the Arduino
     *
     * @param enable true to enable, false otherwise
     */
    public void setArduinoEnabled(boolean enable) {
        Buffer buffer = new Buffer();
        buffer.writeByte(enable ? 1 : 0);
        sendMessage(MessageID.CC_POWER_ARDUINO, buffer);
    }

    /**
     * Read the Arduino power state
     *
     * @param callback the callback for the result, true if the Arduino is enabled, false otherwise.
     */
    public void readArduinoPowerState(final Callback<Boolean> callback) {
        addCallback(MessageID.CC_GET_AR_POWER, callback);
        sendMessageWithoutPayload(MessageID.CC_GET_AR_POWER);
    }

    /**
     * Read the battery level
     *
     * @param callback the callback for the result, the battery level in the range of 0-100%
     */
    public void readBatteryLevel(final Callback<BatteryLevel> callback) {
        mGattClient.getBatteryProfile().getBatteryLevel(new BatteryLevelCallback() {
            @Override
            public void onBatteryLevel(int percentage) {
                callback.onResult(new BatteryLevel(percentage));
            }
        });
    }

    /**
     * End the Arduino's serial gate, allowing serial data from the Arduino to be read by the
     * connected Android client.
     *
     * The serial gate exists to prevent chatty sketches from interfering with the behavior of
     * clients that want to send commands to the ATmega.
     */
    public void endSerialGate() {
        sendMessageWithoutPayload(MessageID.BT_END_GATE);
    }

    /**
     * Programs the Arduino on board the Bean with an Android sketch in hex form. The Bean's sketch
     * name will be set from {@link com.punchthrough.bean.sdk.message.SketchHex#getSketchName()}.
     *
     * @param hex The sketch to be sent to the Bean
     */
    public void programWithSketch(SketchHex hex) {
        SketchMetadata metadata = SketchMetadata.create(hex, new Date());
        Buffer payload = metadata.toPayload();
        sendMessage(MessageID.BL_CMD_START, payload);
    }

    private void handleMessage(byte[] data) {
        Buffer buffer = new Buffer();
        buffer.write(data);
        int type = (buffer.readShort() & 0xffff) & ~(APP_MSG_RESPONSE_BIT);

        if (type == MessageID.SERIAL_DATA.getRawValue()) {
            mBeanListener.onSerialMessageReceived(buffer.readByteArray());

        } else if (type == MessageID.BT_GET_CONFIG.getRawValue()) {
            returnConfig(buffer);

        } else if (type == MessageID.CC_TEMP_READ.getRawValue()) {
            returnTemperature(buffer);

        } else if (type == MessageID.BL_GET_META.getRawValue()) {
            returnMetaData(buffer);

        } else if (type == MessageID.BT_GET_SCRATCH.getRawValue()) {
            returnScratchData(buffer);

        } else if (type == MessageID.CC_LED_READ_ALL.getRawValue()) {
            returnLed(buffer);

        } else if (type == MessageID.CC_ACCEL_READ.getRawValue()) {
            returnAcceleration(buffer);

        } else if (type == MessageID.CC_ACCEL_GET_RANGE.getRawValue()) {
            returnAccelerometerRange(buffer);

        // Ignore CC_LED_WRITE; it appears to be only an ack

        } else if (type == MessageID.CC_GET_AR_POWER.getRawValue()) {
            returnArduinoPowerState(buffer);

        } else if (type == MessageID.BL_STATUS.getRawValue()) {


        } else {
            Log.e(TAG, "Received message of unknown type " + Integer.toHexString(type));
            disconnect();

        }
    }

    private void returnArduinoPowerState(Buffer buffer) {
        Callback<Boolean> callback = getFirstCallback(MessageID.CC_GET_AR_POWER);
        if (callback != null) {
            callback.onResult((buffer.readByte() & 0xff) == 1);
        }
    }

    private void returnAccelerometerRange(Buffer buffer) {
        Callback<Integer> callback = getFirstCallback(MessageID.CC_ACCEL_GET_RANGE);
        if (callback != null) {
            callback.onResult(buffer.readByte() & 0xff);
        }
    }

    private void returnAcceleration(Buffer buffer) {
        Callback<Acceleration> callback = getFirstCallback(MessageID.CC_ACCEL_READ);
        if (callback != null) {
            callback.onResult(Acceleration.fromPayload(buffer));
        }
    }

    private void returnLed(Buffer buffer) {
        Callback<LedColor> callback = getFirstCallback(MessageID.CC_LED_READ_ALL);
        if (callback != null) {
            callback.onResult(LedColor.fromPayload(buffer));
        }
    }

    private void returnScratchData(Buffer buffer) {
        Callback<ScratchData> callback = getFirstCallback(MessageID.BT_GET_SCRATCH);
        if (callback != null) {
            callback.onResult(ScratchData.fromPayload(buffer));
        }
    }

    private void returnMetaData(Buffer buffer) {
        Callback<SketchMetadata> callback = getFirstCallback(MessageID.BL_GET_META);
        if (callback != null) {
            callback.onResult(SketchMetadata.fromPayload(buffer));
        }
    }

    private void returnTemperature(Buffer buffer) {
        Callback<Integer> callback = getFirstCallback(MessageID.CC_TEMP_READ);
        if (callback != null) {
            callback.onResult((int) buffer.readByte());
        }
    }

    private void returnConfig(Buffer data) {
        RadioConfig config = RadioConfig.fromPayload(data);
        Callback<RadioConfig> callback = getFirstCallback(MessageID.BT_GET_CONFIG);
        if (callback != null) {
            callback.onResult(config);
        }
    }

    private void addCallback(MessageID type, Callback<?> callback) {
        List<Callback<?>> callbacks = mCallbacks.get(type);
        if (callbacks == null) {
            callbacks = new ArrayList<>(16);
            mCallbacks.put(type, callbacks);
        }
        callbacks.add(callback);
    }

    @SuppressWarnings("unchecked")
    private <T> Callback<T> getFirstCallback(MessageID type) {
        List<Callback<?>> callbacks = mCallbacks.get(type);
        if (callbacks == null || callbacks.isEmpty()) {
            Log.w(TAG, "Got response without callback!");
            return null;
        }
        return (Callback<T>) callbacks.remove(0);
    }

    private void sendMessage(MessageID type, Message message) {
        Buffer buffer = new Buffer();
        buffer.writeByte((type.getRawValue() >> 8) & 0xff);
        buffer.writeByte(type.getRawValue() & 0xff);
        buffer.write(message.toPayload());
        GattSerialMessage serialMessage = GattSerialMessage.fromPayload(buffer.readByteArray());
        mGattClient.getSerialProfile().sendMessage(serialMessage.getBuffer());
    }

    private void sendMessage(MessageID type, Buffer payload) {
        Buffer buffer = new Buffer();
        buffer.writeByte((type.getRawValue() >> 8) & 0xff);
        buffer.writeByte(type.getRawValue() & 0xff);
        if (payload != null) {
            try {
                buffer.writeAll(payload);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        GattSerialMessage serialMessage = GattSerialMessage.fromPayload(buffer.readByteArray());
        mGattClient.getSerialProfile().sendMessage(serialMessage.getBuffer());
    }

    private void sendMessageWithoutPayload(MessageID type) {
        sendMessage(type, (Buffer) null);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mDevice, 0);
    }
}
