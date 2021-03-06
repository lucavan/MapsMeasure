/*
 * Copyright 2014 Thomas Hoffmann
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.j4velin.mapsmeasure;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Stack;

import org.json.JSONObject;

import com.android.vending.billing.IInAppBillingService;
import com.google.android.gms.common.GooglePlayServicesClient.ConnectionCallbacks;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.location.LocationClient;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMap.OnMapClickListener;
import com.google.android.gms.maps.GoogleMap.OnMarkerClickListener;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.maps.android.SphericalUtil;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.BadParcelableException;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v4.app.FragmentActivity;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.DrawerLayout.DrawerListener;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.TextView.OnEditorActionListener;

public class Map extends FragmentActivity {

	private static enum MeasureType {
		DISTANCE, AREA, ELEVATION
	};

	// the map to draw to
	private GoogleMap mMap;
	private DrawerLayout mDrawerLayout;

	// the stacks - everytime the user touches the map, an entry is pushed
	private Stack<LatLng> trace = new Stack<LatLng>();
	private Stack<Polyline> lines = new Stack<Polyline>();
	private Stack<Marker> points = new Stack<Marker>();

	private Polygon areaOverlay;

	private Pair<Float, Float> altitude;
	private float distance; // in meters
	private MeasureType type; // the currently selected measure type
	private TextView valueTv; // the view displaying the distance/area & unit

	private final static int COLOR_LINE = Color.argb(128, 0, 0, 0), COLOR_POINT = Color.argb(128, 255, 0, 0);
	private final static float LINE_WIDTH = 5f;

	final static NumberFormat formatter_two_dec = NumberFormat.getInstance(Locale.getDefault());
	final static NumberFormat formatter_no_dec = NumberFormat.getInstance(Locale.getDefault());

	boolean metric; // display in metric units

	private LocationClient locationClient;

	private static BitmapDescriptor marker;

	private IInAppBillingService mService;
	private static boolean PRO_VERSION = false;

	ServiceConnection mServiceConn = new ServiceConnection() {
		@Override
		public void onServiceDisconnected(final ComponentName name) {
			mService = null;
		}

		@Override
		public void onServiceConnected(final ComponentName name, final IBinder service) {
			mService = IInAppBillingService.Stub.asInterface(service);
			try {
				Bundle ownedItems = mService.getPurchases(3, getPackageName(), "inapp", null);
				if (ownedItems.getInt("RESPONSE_CODE") == 0) {
					PRO_VERSION = ownedItems.getStringArrayList("INAPP_PURCHASE_ITEM_LIST").contains(
							"de.j4velin.mapsmeasure.billing.pro");
					getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("pro", PRO_VERSION).commit();
				}
			} catch (RemoteException e) {
				Toast.makeText(Map.this, e.getClass().getName() + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
				e.printStackTrace();
			}
		}
	};

	/**
	 * Get the formatted string for the valueTextView.
	 * 
	 * Depending on whether 'showArea' is set, the returned string shows the
	 * distance of the trace or the area between them. If 'showArea' is set,
	 * this call might be expensive as the area is computed here and not cached.
	 * 
	 * @return the formatted text for the valueTextView
	 */
	private String getFormattedString() {
		if (type == MeasureType.DISTANCE) {
			if (metric) {
				if (distance > 1000)
					return formatter_two_dec.format(distance / 1000) + " km";
				else
					return formatter_two_dec.format(Math.max(0, distance)) + " m";
			} else {
				if (distance > 1609)
					return formatter_two_dec.format(distance / 1609.344f) + " mi";
				else
					return formatter_two_dec.format(Math.max(0, distance / 0.3048f)) + " ft";
			}
		} else if (type == MeasureType.AREA) {
			double area;
			if (areaOverlay != null)
				areaOverlay.remove();
			if (trace.size() >= 3) {
				area = SphericalUtil.computeArea(trace);
				areaOverlay = mMap.addPolygon(new PolygonOptions().addAll(trace).strokeWidth(0).fillColor(COLOR_POINT));
			} else {
				area = 0;
			}
			if (metric) {
				if (area > 1000000)
					return formatter_two_dec.format(Math.max(0, area / 1000000d)) + " km�";
				else
					return formatter_no_dec.format(Math.max(0, area)) + " m�";
			} else {
				if (area >= 2589989)
					return formatter_two_dec.format(Math.max(0, area / 2589988.110336d)) + " mi�";
				else
					return formatter_no_dec.format(Math.max(0, area / 0.09290304d)) + " ft�";
			}
		} else if (type == MeasureType.ELEVATION) {
			if (altitude == null) {
				final Handler h = new Handler();
				new Thread(new Runnable() {
					@Override
					public void run() {
						altitude = Util.getElevation(trace);
						h.post(new Runnable() {
							@Override
							public void run() {
								if (altitude == null) {
									Dialogs.getElevationErrorDialog(Map.this).show();
									changeType(MeasureType.DISTANCE);
								} else {
									updateValueText();
								}
							}
						});
					}
				}).start();
				return "Loading...";
			} else {
				String re = metric ? formatter_two_dec.format(altitude.first) + " m\u2B06, "
						+ formatter_two_dec.format(-1 * altitude.second) + " m\u2B07" : formatter_two_dec
						.format(altitude.first / 0.3048f)
						+ " ft\u2B06"
						+ formatter_two_dec.format(-1 * altitude.second / 0.3048f) + " ft\u2B07";
				if (!trace.isEmpty()) {
					try {
						float lastPoint = Util.getAltitude(trace.peek(), null, null);
						if (lastPoint > -Float.MAX_VALUE) {
							re += "\n"
									+ (metric ? formatter_two_dec.format(lastPoint) + " m" : formatter_two_dec
											.format(lastPoint / 0.3048f) + " ft");
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
				altitude = null;
				return re;
			}
		} else {
			return "not yet supported";
		}
	}

	@Override
	protected void onRestoreInstanceState(final Bundle savedInstanceState) {
		super.onRestoreInstanceState(savedInstanceState);
		try {
			metric = savedInstanceState.getBoolean("metric");
			@SuppressWarnings("unchecked")
			// Casting to Stack<LatLng> apparently results in
			// "java.lang.ClassCastException: java.util.ArrayList cannot be cast to java.util.Stack"
			// on some devices
			List<LatLng> tmp = (List<LatLng>) savedInstanceState.getSerializable("trace");
			Iterator<LatLng> it = tmp.iterator();
			while (it.hasNext()) {
				addPoint(it.next());
			}
			mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(savedInstanceState.getDouble("position-lat"),
					savedInstanceState.getDouble("position-lon")), savedInstanceState.getFloat("position-zoom")));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	protected void onSaveInstanceState(final Bundle outState) {
		outState.putSerializable("trace", trace);
		outState.putBoolean("metric", metric);
		if (mMap != null) { // might be null if there is an issue with Google
							// Play Services
			outState.putDouble("position-lon", mMap.getCameraPosition().target.longitude);
			outState.putDouble("position-lat", mMap.getCameraPosition().target.latitude);
			outState.putFloat("position-zoom", mMap.getCameraPosition().zoom);
		}
		super.onSaveInstanceState(outState);
	}

	/**
	 * Adds a new point, calculates the new distance and draws the point and a
	 * line to it
	 * 
	 * @param p
	 *            the new point
	 */
	void addPoint(final LatLng p) {
		if (!trace.isEmpty()) {
			lines.push(mMap.addPolyline(new PolylineOptions().color(COLOR_LINE).width(LINE_WIDTH).add(trace.peek()).add(p)));
			distance += SphericalUtil.computeDistanceBetween(p, trace.peek());
		}
		points.push(drawMarker(p));
		trace.push(p);
		updateValueText();
	}

	/**
	 * Resets the map by removing all points, lines and setting the text to 0
	 */
	void clear() {
		mMap.clear();
		trace.clear();
		lines.clear();
		points.clear();
		distance = 0;
		updateValueText();
	}

	/**
	 * Removes the last added point, the line to it and updates the distance
	 */
	private void removeLast() {
		if (trace.isEmpty())
			return;
		points.pop().remove();
		LatLng remove = trace.pop();
		if (!trace.isEmpty())
			distance -= SphericalUtil.computeDistanceBetween(remove, trace.peek());
		if (!lines.isEmpty())
			lines.pop().remove();
		updateValueText();
	}

	@SuppressLint("NewApi")
	@Override
	public void onCreate(final Bundle savedInstanceState) {
		try {
			super.onCreate(savedInstanceState);
		} catch (final BadParcelableException bpe) {
			bpe.printStackTrace();
		}
		setContentView(R.layout.activity_map);

		formatter_no_dec.setMaximumFractionDigits(0);
		formatter_two_dec.setMaximumFractionDigits(2);

		final SharedPreferences prefs = getSharedPreferences("settings", Context.MODE_PRIVATE);

		// use metric a the default everywhere, except in the US
		metric = prefs.getBoolean("metric", !Locale.getDefault().equals(Locale.US));

		final View topCenterOverlay = findViewById(R.id.topCenterOverlay);
		mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);

		final View menuButton = findViewById(R.id.menu);
		if (menuButton != null) {
			menuButton.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(final View v) {
					mDrawerLayout.openDrawer(GravityCompat.START);
				}
			});
		}

		if (mDrawerLayout != null) {
			mDrawerLayout.setDrawerShadow(R.drawable.drawer_shadow, GravityCompat.START);

			mDrawerLayout.setDrawerListener(new DrawerListener() {

				private boolean menuButtonVisible = true;

				@Override
				public void onDrawerStateChanged(int newState) {

				}

				@TargetApi(Build.VERSION_CODES.HONEYCOMB)
				@Override
				public void onDrawerSlide(final View drawerView, final float slideOffset) {
					if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB)
						topCenterOverlay.setAlpha(1 - slideOffset);
					if (menuButtonVisible && menuButton != null && slideOffset > 0) {
						menuButton.setVisibility(View.INVISIBLE);
						menuButtonVisible = false;
					}
				}

				@Override
				public void onDrawerOpened(final View drawerView) {
					if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.HONEYCOMB)
						topCenterOverlay.setVisibility(View.INVISIBLE);
				}

				@Override
				public void onDrawerClosed(final View drawerView) {
					if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.HONEYCOMB)
						topCenterOverlay.setVisibility(View.VISIBLE);
					if (menuButton != null) {
						menuButton.setVisibility(View.VISIBLE);
						menuButtonVisible = true;
					}
				}
			});
		}

		mMap = ((SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map)).getMap();

		if (mMap == null) {
			Dialog d = GooglePlayServicesUtil.getErrorDialog(GooglePlayServicesUtil.isGooglePlayServicesAvailable(this), this, 0);
			d.setOnDismissListener(new OnDismissListener() {
				@Override
				public void onDismiss(DialogInterface dialog) {
					finish();
				}
			});
			d.show();
			return;
		}

		marker = BitmapDescriptorFactory.fromResource(R.drawable.marker);
		mMap.setOnMarkerClickListener(new OnMarkerClickListener() {
			@Override
			public boolean onMarkerClick(final Marker click) {
				addPoint(click.getPosition());
				return true;
			}
		});

		// check if open with csv file
		if (Intent.ACTION_VIEW.equals(getIntent().getAction())) {
			try {
				Util.loadFromFile(getIntent().getData(), this);
				if (!trace.isEmpty())
					mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(trace.peek(), 16));
			} catch (IOException e) {
				Toast.makeText(this, getString(R.string.error, e.getClass().getSimpleName() + "\n" + e.getMessage()),
						Toast.LENGTH_LONG).show();
				e.printStackTrace();
			}
		}
		locationClient = new LocationClient(this, new ConnectionCallbacks() {
			@Override
			public void onDisconnected() {

			}

			@Override
			public void onConnected(final Bundle b) {
				Location l = locationClient.getLastLocation();
				// only move to current position if not zoomed in at another
				// location already
				if (l != null && mMap.getCameraPosition().zoom <= 2) {
					mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(l.getLatitude(), l.getLongitude()), 16));
				}
				locationClient.disconnect();
			}
		}, null);
		locationClient.connect();

		valueTv = (TextView) findViewById(R.id.distance);
		updateValueText();
		valueTv.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(final View v) {
				if (type == MeasureType.DISTANCE)
					changeType(MeasureType.AREA);
				// only switch to elevation mode is an internet connection is
				// available and user has access to this feature
				else if (type == MeasureType.AREA && Util.checkInternetConnection(Map.this) && PRO_VERSION)
					changeType(MeasureType.ELEVATION);
				else
					changeType(MeasureType.DISTANCE);
			}
		});

		View delete = findViewById(R.id.delete);
		delete.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(final View v) {
				removeLast();
			}
		});
		delete.setOnLongClickListener(new OnLongClickListener() {
			@Override
			public boolean onLongClick(final View v) {
				AlertDialog.Builder builder = new AlertDialog.Builder(Map.this);
				builder.setMessage(getString(R.string.delete_all, trace.size()));
				builder.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						clear();
						dialog.dismiss();
					}
				});
				builder.setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
					}
				});
				builder.create().show();
				return true;
			}
		});

		mMap.setOnMapClickListener(new OnMapClickListener() {
			@Override
			public void onMapClick(final LatLng center) {
				addPoint(center);
			}
		});

		// KitKat translucent decor enabled? -> Add some margin/padding to the
		// drawer and the map
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {

			int statusbar = Util.getStatusBarHeight(this);

			FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) topCenterOverlay.getLayoutParams();
			lp.setMargins(10, statusbar + 10, 0, 0);
			topCenterOverlay.setLayoutParams(lp);

			DisplayMetrics total, available;
			total = new DisplayMetrics();
			available = new DisplayMetrics();
			getWindowManager().getDefaultDisplay().getMetrics(available);
			getWindowManager().getDefaultDisplay().getRealMetrics(total);

			// on most devices and in most orientations, the navigation bar
			// should be at the bottom and therefore reduces the available
			// display height
			int navBarHeight = Math.max(0, total.heightPixels - available.heightPixels - Util.getStatusBarHeight(this));

			if (getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
				// in landscape on phones, the navigation bar might be at the
				// right side, reducing the available display width
				int navBarWidth = total.widthPixels - available.widthPixels;

				mMap.setPadding(mDrawerLayout == null ? Util.dpToPx(this, 200) : 0, statusbar, navBarWidth, navBarHeight);
				findViewById(R.id.left_drawer).setPadding(0, statusbar + 10, 0, navBarHeight);
				if (menuButton != null)
					menuButton.setPadding(0, 0, 0, 10 + navBarHeight);
			} else {
				mMap.setPadding(0, statusbar, 0, navBarHeight);
				findViewById(R.id.left_drawer).setPadding(0, statusbar + 10, 0, navBarHeight);
				if (menuButton != null)
					menuButton.setPadding(0, 0, 0, 10 + navBarHeight);
			}
		}

		mMap.setMyLocationEnabled(true);

		// Drawer stuff
		((EditText) findViewById(R.id.search)).setOnEditorActionListener(new OnEditorActionListener() {
			@Override
			public boolean onEditorAction(final TextView v, int actionId, final KeyEvent event) {
				if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) {
					new GeocoderTask().execute(v.getText().toString());
					InputMethodManager inputManager = (InputMethodManager) Map.this
							.getSystemService(Context.INPUT_METHOD_SERVICE);
					inputManager.hideSoftInputFromWindow(Map.this.getCurrentFocus().getWindowToken(),
							InputMethodManager.HIDE_NOT_ALWAYS);
					if (mDrawerLayout != null)
						mDrawerLayout.closeDrawers();
				}
				return true;
			}
		});

		final View metricTV = findViewById(R.id.metric);
		metricTV.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(final View v) {
				Dialogs.getUnits(Map.this, distance, SphericalUtil.computeArea(trace)).show();
				if (mDrawerLayout != null)
					mDrawerLayout.closeDrawers();
			}
		});

		toggleSatelliteView(prefs.getBoolean("satellite", false));
		changeType(MeasureType.DISTANCE);

		findViewById(R.id.mapview_map).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(final View v) {
				toggleSatelliteView(false);
			}
		});
		findViewById(R.id.mapview_satellite).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(final View v) {
				toggleSatelliteView(true);
			}
		});
		findViewById(R.id.measure_area).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(final View v) {
				changeType(MeasureType.AREA);
			}
		});
		findViewById(R.id.measure_distance).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(final View v) {
				changeType(MeasureType.DISTANCE);
			}
		});
		findViewById(R.id.measure_elevation).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(final View v) {
				if (PRO_VERSION) {
					changeType(MeasureType.ELEVATION);
				} else {
					Dialogs.getElevationAccessDialog(Map.this, mService).show();
				}
			}
		});
		findViewById(R.id.savenshare).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				Dialogs.getSaveNShare(Map.this, trace).show();
				if (mDrawerLayout != null)
					mDrawerLayout.closeDrawers();
			}
		});
		findViewById(R.id.about).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				Dialogs.getAbout(Map.this).show();
				if (mDrawerLayout != null)
					mDrawerLayout.closeDrawers();
			}
		});
		findViewById(R.id.moreapps).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				try {
					startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=pub:j4velin"))
							.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
				} catch (ActivityNotFoundException anf) {
					startActivity(new Intent(Intent.ACTION_VIEW, Uri
							.parse("https://play.google.com/store/apps/developer?id=j4velin"))
							.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
				}
			}
		});

		PRO_VERSION = PRO_VERSION ? true : prefs.getBoolean("pro", false);
		if (!PRO_VERSION) {
			bindService(new Intent("com.android.vending.billing.InAppBillingService.BIND"), mServiceConn,
					Context.BIND_AUTO_CREATE);
		}
	}

	/**
	 * Change the "type" of measuring: Distance, Area or Altitude
	 * 
	 * @param newType
	 *            the type to change to
	 */
	private void changeType(final MeasureType newType) {
		type = newType;
		findViewById(R.id.measure_area).setBackgroundResource(
				newType == MeasureType.AREA ? R.drawable.background_selected : R.drawable.background_normal);
		findViewById(R.id.measure_distance).setBackgroundResource(
				newType == MeasureType.DISTANCE ? R.drawable.background_selected : R.drawable.background_normal);
		findViewById(R.id.measure_elevation).setBackgroundResource(
				newType == MeasureType.ELEVATION ? R.drawable.background_selected : R.drawable.background_normal);
		updateValueText();
		if (mDrawerLayout != null)
			mDrawerLayout.closeDrawers();
		if (newType != MeasureType.AREA) {
			if (areaOverlay != null)
				areaOverlay.remove();
		}
	}

	/**
	 * Change between normal map and satellite hybrid view
	 * 
	 * @param enable
	 *            true to switch to hybrid view
	 */
	private void toggleSatelliteView(boolean enable) {
		mMap.setMapType(enable ? GoogleMap.MAP_TYPE_HYBRID : GoogleMap.MAP_TYPE_NORMAL);
		findViewById(R.id.mapview_satellite).setBackgroundResource(
				enable ? R.drawable.background_selected : R.drawable.background_normal);
		findViewById(R.id.mapview_map).setBackgroundResource(
				enable ? R.drawable.background_normal : R.drawable.background_selected);
		if (mDrawerLayout != null)
			mDrawerLayout.closeDrawers();
		getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("satellite", enable).commit();
	}

	/**
	 * Draws a marker at the given point.
	 * 
	 * Should be called when the users touches the map and adds an entry to the
	 * stacks
	 * 
	 * @param center
	 *            the point where the user clicked
	 * @return the drawn Polygon
	 */
	private Marker drawMarker(final LatLng center) {
		return mMap.addMarker(new MarkerOptions().position(center).flat(true).anchor(0.5f, 0.5f).icon(marker));
	}

	/**
	 * 
	 * Based on
	 * http://wptrafficanalyzer.in/blog/android-geocoding-showing-user-input
	 * -location-on-google-map-android-api-v2/
	 * 
	 * @author George Mathew
	 * 
	 */
	private class GeocoderTask extends AsyncTask<String, Void, Address> {

		@Override
		protected Address doInBackground(final String... locationName) {
			// Creating an instance of Geocoder class
			Geocoder geocoder = new Geocoder(getBaseContext());
			try {
				// Get only the best result that matches the input text
				List<Address> addresses = geocoder.getFromLocationName(locationName[0], 1);
				return addresses != null && !addresses.isEmpty() ? addresses.get(0) : null;
			} catch (IOException e) {
				e.printStackTrace();
				return null;
			}
		}

		@Override
		protected void onPostExecute(final Address address) {
			if (address == null) {
				Toast.makeText(getBaseContext(), R.string.no_location_found, Toast.LENGTH_SHORT).show();
			} else {
				mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(address.getLatitude(), address.getLongitude()),
						Math.max(10, mMap.getCameraPosition().zoom)));
			}
		}
	}

	/**
	 * Updates the valueTextView at the top of the screen
	 */
	void updateValueText() {
		if (valueTv != null)
			valueTv.setText(getFormattedString());
	}

	@Override
	public boolean onPrepareOptionsMenu(final Menu menu) {
		if (mDrawerLayout == null)
			return true;
		if (mDrawerLayout.isDrawerOpen(GravityCompat.START))
			mDrawerLayout.closeDrawers();
		else
			mDrawerLayout.openDrawer(GravityCompat.START);
		return false;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (mService != null) {
			unbindService(mServiceConn);
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == 42 && resultCode == RESULT_OK) {
			if (data.getIntExtra("RESPONSE_CODE", 0) == 0) {
				try {
					JSONObject jo = new JSONObject(data.getStringExtra("INAPP_PURCHASE_DATA"));
					PRO_VERSION = jo.getString("productId").equals("de.j4velin.mapsmeasure.pro")
							&& jo.getString("developerPayload").equals(getPackageName());
					getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("pro", PRO_VERSION).commit();
					changeType(MeasureType.ELEVATION);
				} catch (Exception e) {
					Toast.makeText(this, e.getClass().getName() + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
					e.printStackTrace();
				}
			}
		}
	}
}
