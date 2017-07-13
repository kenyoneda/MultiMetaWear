/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.example.android.bluetoothlegatt;

import android.app.Activity;
import android.app.ListActivity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.mbientlab.metawear.Data;
import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.Route;
import com.mbientlab.metawear.Subscriber;
import com.mbientlab.metawear.android.BtleService;
import com.mbientlab.metawear.builder.RouteBuilder;
import com.mbientlab.metawear.builder.RouteComponent;
import com.mbientlab.metawear.data.Acceleration;
import com.mbientlab.metawear.data.AngularVelocity;
import com.mbientlab.metawear.module.Accelerometer;
import com.mbientlab.metawear.module.AccelerometerBmi160;
import com.mbientlab.metawear.module.AccelerometerBosch;
import com.mbientlab.metawear.module.GyroBmi160;
import com.mbientlab.metawear.module.Logging;

import org.w3c.dom.Text;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Exchanger;

import bolts.Continuation;
import bolts.Task;

/**
 * Activity for scanning and displaying available Bluetooth LE devices.
 */
public class DeviceScanActivity extends ListActivity implements ServiceConnection, MWDeviceConfirmationFragment.DeviceConfirmCallback {
    private LeDeviceListAdapter mLeDeviceListAdapter;
    private BluetoothAdapter mBluetoothAdapter;
    private boolean mScanning;
    private Handler mHandler;
    private HashSet<UUID> filterServiceUuids;
    private BtleService.LocalBinder serviceBinder;
    private HashSet<MetaWearBoard> metaWearBoards = new HashSet<>();
    private HashMap<String, ArrayList<SensorRecord>> datamap = new HashMap<>();

    private Button mStartButton;
    private Button mStopButton;
    private Button mResetButton;

    private static final int REQUEST_ENABLE_BT = 1;
    // Stops scanning after 10 seconds.
    private static final long SCAN_PERIOD = 10000;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActionBar().setTitle(R.string.title_devices);
        setContentView(R.layout.main);
        mHandler = new Handler();
        filterServiceUuids = getUuids();

        mStartButton = (Button) findViewById(R.id.startbutton);
        mStopButton = (Button) findViewById(R.id.stopbutton);
        addListeners();

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
        }

        // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
        // BluetoothAdapter through BluetoothManager.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        // Checks if Bluetooth is supported on the device.
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        getApplicationContext().bindService(new Intent(this, BtleService.class),
                this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        if (!mScanning) {
            menu.findItem(R.id.menu_stop).setVisible(false);
            menu.findItem(R.id.menu_scan).setVisible(true);
            menu.findItem(R.id.menu_refresh).setActionView(null);
        } else {
            menu.findItem(R.id.menu_stop).setVisible(true);
            menu.findItem(R.id.menu_scan).setVisible(false);
            menu.findItem(R.id.menu_refresh).setActionView(
                    R.layout.actionbar_indeterminate_progress);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_scan:
                mLeDeviceListAdapter.clear();
                scanLeDevice(true);
                break;
            case R.id.menu_stop:
                scanLeDevice(false);
                break;
        }
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
        if (!mBluetoothAdapter.isEnabled()) {
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        }

        // Initializes list view adapter.
        mLeDeviceListAdapter = new LeDeviceListAdapter();
        setListAdapter(mLeDeviceListAdapter);
        scanLeDevice(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // User chose not to enable Bluetooth.
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onPause() {
        super.onPause();
        scanLeDevice(false);
        mLeDeviceListAdapter.clear();
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        final BluetoothDevice device = mLeDeviceListAdapter.getDevice(position);
        if (device == null) {
            return;
        }
        final MetaWearBoard board = serviceBinder.getMetaWearBoard(device);
        if (board == null) {
            return;
        }
        else {
            board.connectAsync().continueWith(new Continuation<Void, Void>() {
                @Override
                public Void then(Task<Void> task) throws Exception {
                    if (task.isFaulted()) {
                        Log.i("MainActivity", "Failed to connect");
                    }
                    else {
                        MWDeviceConfirmationFragment confirmation = new MWDeviceConfirmationFragment();
                        confirmation.flashDeviceLight(board, getFragmentManager());
                        metaWearBoards.add(board);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(getApplicationContext(), R.string.toast_connected, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                    return null;
                }
            });
        }
        TextView status = (TextView) v.findViewById(R.id.device_status);
        status.setText(R.string.connected);
    }

    public void pairDevice() {}
    public void dontPairDevice() {}

    private void scanLeDevice(final boolean enable) {
        if (enable) {
            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mScanning = false;
                    mBluetoothAdapter.stopLeScan(mLeScanCallback);
                    invalidateOptionsMenu();
                }
            }, SCAN_PERIOD);

            mScanning = true;
            mBluetoothAdapter.startLeScan(mLeScanCallback);
        } else {
            mScanning = false;
            mBluetoothAdapter.stopLeScan(mLeScanCallback);
        }
        invalidateOptionsMenu();
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
        // Typecast the binder to the service's LocalBinder class
        serviceBinder = (BtleService.LocalBinder) iBinder;
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) { }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Unbind the service when the activity is destroyed
        getApplicationContext().unbindService(this);
    }

    private void addListeners() {
        mStartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                for (final MetaWearBoard board : metaWearBoards) {
                    datamap.put(board.getMacAddress() + "_ACCEL", new ArrayList<SensorRecord>());
                    datamap.put(board.getMacAddress() + "_GYRO", new ArrayList<SensorRecord>());

                    final AccelerometerBmi160 accelerometer = board.getModule(AccelerometerBmi160.class);
                    final GyroBmi160 gyroscope = board.getModule(GyroBmi160.class);

                    accelerometer.configure()
                            .odr(AccelerometerBmi160.OutputDataRate.ODR_25_HZ)
                            .range(AccelerometerBosch.AccRange.AR_4G)
                            .commit();
                    accelerometer.acceleration().addRouteAsync(new RouteBuilder() {
                        @Override
                        public void configure(RouteComponent source) {
                            source.stream(new Subscriber() {
                                @Override
                                public void apply(Data data, Object... env) {
                                    Acceleration accel = data.value(Acceleration.class);
                                    datamap.get(board.getMacAddress() + "_ACCEL")
                                            .add(new SensorRecord(data.formattedTimestamp(), accel.x(), accel.y(), accel.z()));
                                    Log.i("Accel: ", data.formattedTimestamp() + ", " + accel.x() + ", " + accel.y() + ", " + accel.z());
                                }
                            });
                        }
                    }).continueWith(new Continuation<Route, Void>() {
                        @Override
                        public Void then(Task<Route> task) throws Exception {
                            accelerometer.acceleration().start();
                            accelerometer.start();
                            return null;
                        }
                    });

                    gyroscope.configure()
                            .odr(GyroBmi160.OutputDataRate.ODR_25_HZ)
                            .range(GyroBmi160.Range.FSR_250)
                            .commit();
                    gyroscope.angularVelocity().addRouteAsync(new RouteBuilder() {
                        @Override
                        public void configure(RouteComponent source) {
                            source.stream(new Subscriber() {
                                @Override
                                public void apply(Data data, Object... env) {
                                    AngularVelocity gyro = data.value(AngularVelocity.class);
                                    datamap.get(board.getMacAddress() + "_GYRO")
                                            .add(new SensorRecord(data.formattedTimestamp(), gyro.x(), gyro.y(), gyro.z()));
                                    Log.i("Gyro: ", data.formattedTimestamp() + ", " + gyro.x() + ", " + gyro.y() + ", " + gyro.z());
                                }
                            });
                        }
                    }).continueWith(new Continuation<Route, Void>() {
                        @Override
                        public Void then(Task<Route> task) throws Exception {
                            gyroscope.angularVelocity().start();
                            gyroscope.start();
                            return null;
                        }
                    });
                }
            }
        });

        mStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                for (MetaWearBoard board : metaWearBoards) {
                    final AccelerometerBmi160 accelerometer = board.getModule(AccelerometerBmi160.class);
                    final GyroBmi160 gyroscope = board.getModule(GyroBmi160.class);

                    accelerometer.acceleration().stop();
                    accelerometer.stop();
                    gyroscope.angularVelocity().stop();
                    gyroscope.stop();
                    board.tearDown();
                    board.disconnectAsync().continueWith(new Continuation<Void, Void>() {
                        @Override
                        public Void then(Task<Void> task) throws Exception {
                            Log.i("MainActivity", "Disconnected");
                            return null;
                        }
                    });
                }

                writeFiles();
            }
        });
    }

    private void writeFiles() {
        File directory = new File(Environment.getExternalStorageDirectory()
            + File.separator + "PERL LAB");
        if (!directory.isDirectory()) {
            Log.i("Main", "Directory created: " + directory.getAbsolutePath());
            directory.mkdirs();
        }

        for (Map.Entry<String, ArrayList<SensorRecord>> entry : datamap.entrySet()) {
            String filename = entry.getKey().replace(":", "-");
            String timestamp = entry.getValue().get(0).getTimestamp().substring(0, 16).replace(":", "");
            File file = new File(directory, filename + "_" + timestamp + ".txt");
            Log.i("Main", "Filename: " + file.getAbsolutePath());

            try {
                BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(file));
                for (SensorRecord record : entry.getValue()) {
                    bufferedWriter.write(record.toString());
                    bufferedWriter.newLine();
                }
                bufferedWriter.close();
            }
            catch (IOException e) {
                e.printStackTrace();
            }

            // Discover new files
            String[] path = {file.getAbsolutePath()};
            MediaScannerConnection.scanFile(getApplicationContext(), path, null, null);
        }
    }

    // Adapter for holding devices found through scanning.
    private class LeDeviceListAdapter extends BaseAdapter {
        private ArrayList<BluetoothDevice> mLeDevices;
        private LayoutInflater mInflator;



        public LeDeviceListAdapter() {
            super();
            mLeDevices = new ArrayList<BluetoothDevice>();
            mInflator = DeviceScanActivity.this.getLayoutInflater();
        }

        public void addDevice(BluetoothDevice device) {
            if(!mLeDevices.contains(device)) {
                mLeDevices.add(device);
            }
        }

        public BluetoothDevice getDevice(int position) {
            return mLeDevices.get(position);
        }

        public void clear() {
            mLeDevices.clear();
        }

        @Override
        public int getCount() {
            return mLeDevices.size();
        }

        @Override
        public Object getItem(int i) {
            return mLeDevices.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            ViewHolder viewHolder;
            // General ListView optimization code.
            if (view == null) {
                view = mInflator.inflate(R.layout.listitem_device, null);
                viewHolder = new ViewHolder();
                viewHolder.deviceAddress = (TextView) view.findViewById(R.id.device_address);
                viewHolder.deviceName = (TextView) view.findViewById(R.id.device_name);
                viewHolder.deviceStatus = (TextView) view.findViewById(R.id.device_status);
                view.setTag(viewHolder);
            } else {
                viewHolder = (ViewHolder) view.getTag();
            }

            BluetoothDevice device = mLeDevices.get(i);
            final String deviceName = device.getName();
            if (deviceName != null && deviceName.length() > 0) {
                viewHolder.deviceName.setText(deviceName);
            }
            else {
                viewHolder.deviceName.setText(R.string.unknown_device);
            }
            viewHolder.deviceAddress.setText(device.getAddress());
            viewHolder.deviceStatus.setText(R.string.disconnected);

            return view;
        }
    }

    // Get MetaWear Boards only
    private HashSet<UUID> getUuids() {
        UUID[] uuids = new UUID[] {UUID.fromString("326a9000-85cb-9195-d9dd-464cfbbae75a")};
        HashSet<UUID> filterUuids = new HashSet<UUID>();
        filterUuids.addAll(Arrays.asList(uuids));
        return filterUuids;
    }

    // Credit to BleToolbox from https://github.com/mbientlab-projects/BleToolbox
    private BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {
        private void foundDevice(final BluetoothDevice btDevice, final int rssi) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mLeDeviceListAdapter.addDevice(btDevice);
                    mLeDeviceListAdapter.notifyDataSetChanged();
                }
            });
        }

        @Override
        public void onLeScan(BluetoothDevice bluetoothDevice, int rssi, byte[] scanRecord) {
            ///< Service UUID parsing code taking from stack overflow= http://stackoverflow.com/a/24539704

            ByteBuffer buffer= ByteBuffer.wrap(scanRecord).order(ByteOrder.LITTLE_ENDIAN);
            boolean stop= false;
            while (!stop && buffer.remaining() > 2) {
                byte length = buffer.get();
                if (length == 0) break;

                byte type = buffer.get();
                switch (type) {
                    case 0x02: // Partial list of 16-bit UUIDs
                    case 0x03: // Complete list of 16-bit UUIDs
                        while (length >= 2) {
                            UUID serviceUUID = UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", buffer.getShort()));
                            stop = filterServiceUuids.isEmpty() || filterServiceUuids.contains(serviceUUID);
                            if (stop) {
                                foundDevice(bluetoothDevice, rssi);
                            }

                            length -= 2;
                        }
                        break;

                    case 0x06: // Partial list of 128-bit UUIDs
                    case 0x07: // Complete list of 128-bit UUIDs
                        while (!stop && length >= 16) {
                            long lsb= buffer.getLong(), msb= buffer.getLong();
                            stop= filterServiceUuids.isEmpty() || filterServiceUuids.contains(new UUID(msb, lsb));
                            if (stop) {
                                foundDevice(bluetoothDevice, rssi);
                            }
                            length -= 16;
                        }
                        break;

                    default:
                        buffer.position(buffer.position() + length - 1);
                        break;
                }
            }

            if (!stop && filterServiceUuids.isEmpty()) {
                foundDevice(bluetoothDevice, rssi);
            }
        }
    };

    static class ViewHolder {
        TextView deviceName;
        TextView deviceAddress;
        TextView deviceStatus;
    }
}