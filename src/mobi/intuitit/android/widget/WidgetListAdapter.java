package mobi.intuitit.android.widget;

import java.util.ArrayList;
import java.util.HashMap;

import mobi.intuitit.android.content.LauncherIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.text.Html;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * 
 * @author Koxx
 * 
 */
public class WidgetListAdapter extends BaseAdapter {

	static final String LOG_TAG = "LauncherPP_WLA";

	static final int IMPOSSIBLE_INDEX = -100;

	private static final boolean LOGD = true;

	final LayoutInflater mInflater;

	final int mItemLayoutId;

	final int mAppWidgetId;
	final int mListViewId;

	ItemMapping[] mItemMappings;

	boolean mAllowRequery = true;

	private ContentResolver mContentResolver;
	private Intent mIntent;

	class RowElement {
		// item data
		public String text;
		public byte[] imageBlobData;
		public String imageUri;
		public int imageResId;
		public String tag;
	}

	class RowElementsList {
		HashMap<Integer, RowElement> singleRowElementsList = new HashMap<Integer, RowElement>();
	}

	public ArrayList<RowElementsList> rowsElementsList = new ArrayList<RowElementsList>();

	class ItemMapping {
		int type;
		int layoutId;
		int defaultResource;
		int index;
		boolean clickable;

		/**
		 * 
		 * @param t
		 *            view type
		 * @param l
		 *            layout id
		 * @param i
		 *            index
		 * @param r
		 *            default resource
		 * @param u
		 *            clickable
		 */
		ItemMapping(int t, int l, int i, int r, boolean u) {
			type = t;
			layoutId = l;
			defaultResource = r;
			index = i;
			clickable = u;
		}

		ItemMapping(int t, int l, int i) {
			type = t;
			layoutId = l;
			index = i;
			defaultResource = -1;
			clickable = false;
		}
	}

	public final boolean mItemChildrenClickable;
	final int mItemActionUriIndex;
	ComponentName mAppWidgetProvider;

	// Need handler for callbacks to the UI thread
	final Handler mHandler = new Handler();

	// Create runnable for posting
	final Runnable mUpdateResults = new Runnable() {
		public void run() {
			updateResultsInUi();
		}
	};

	private void updateResultsInUi() {
		notifyDataSetInvalidated();
	}

	/**
	 * 
	 * @param context
	 *            remote context
	 * @param c
	 *            cursor for reading data
	 * @param intent
	 *            broadcast intent initiated the replacement, don't save it
	 * @param appWidgetId
	 * @param listViewId
	 */
	public WidgetListAdapter(Context context, Intent intent, ComponentName provider, int appWidgetId, int listViewId)
			throws IllegalArgumentException {
		super();

		mAppWidgetId = appWidgetId;
		mListViewId = listViewId;
		mContentResolver = context.getContentResolver();
		mIntent = intent;
		mAppWidgetProvider = provider;
		mInflater = LayoutInflater.from(context);

		// verify is contentProvider requery is allowed
		mAllowRequery = intent.getBooleanExtra(LauncherIntent.Extra.Scroll.EXTRA_DATA_PROVIDER_ALLOW_REQUERY, false);

		// Get the layout if for items
		mItemLayoutId = intent.getIntExtra(LauncherIntent.Extra.Scroll.EXTRA_ITEM_LAYOUT_ID, -1);
		if (mItemLayoutId <= 0)
			throw (new IllegalArgumentException("The passed layout id is illegal"));

		mItemChildrenClickable = intent.getBooleanExtra(LauncherIntent.Extra.Scroll.EXTRA_ITEM_CHILDREN_CLICKABLE,
				false);

		mItemActionUriIndex = intent.getIntExtra(LauncherIntent.Extra.Scroll.EXTRA_ITEM_ACTION_VIEW_URI_INDEX, -1);

		// Generate item mapping
		generateItemMapping(intent);

		// Generate data cache from content provider
		if (mRegenerateCacheThread == null)
			mRegenerateCacheThread = new RegenerateCacheThread();
		mRegenerateCacheThread.run();

	}

	class RegenerateCacheThread implements Runnable {

		public void run() {
			if (LOGD)
				Log.d(LOG_TAG, "RegenerateCacheThread start");
			generateCache();
			mHandler.post(mUpdateResults);
			if (LOGD)
				Log.d(LOG_TAG, "RegenerateCacheThread end");
		}
	}

	RegenerateCacheThread mRegenerateCacheThread = null;

	/**
	 * Collect arrays and put them together
	 * 
	 * @param t
	 * @param ids
	 * @param c
	 * @param u
	 *            uri indices; could be zero, IMPOSSIBLE_INDEX will be used
	 */
	private void generateItemMapping(Intent intent) {

		// Read the mapping data
		int[] viewTypes = intent.getIntArrayExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_VIEW_TYPES);
		int[] viewIds = intent.getIntArrayExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_VIEW_IDS);
		int[] cursorIndices = intent.getIntArrayExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_CURSOR_INDICES);
		int[] defaultResources = intent.getIntArrayExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_DEFAULT_RESOURCES);
		boolean[] viewClickable = intent.getBooleanArrayExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_VIEW_CLICKABLE);

		// Check
		if (viewTypes == null || viewIds == null || cursorIndices == null)
			throw (new IllegalArgumentException("A mapping component is missing"));

		if (viewTypes.length == viewIds.length && viewTypes.length == cursorIndices.length) {
		} else
			throw (new IllegalArgumentException("Mapping inconsistent"));

		// Init mapping array
		final int size = viewTypes.length;
		mItemMappings = new ItemMapping[size];
		for (int i = size - 1; i >= 0; i--)
			mItemMappings[i] = new ItemMapping(viewTypes[i], viewIds[i], cursorIndices[i]);

		// Put extra data in if they are available
		if (viewClickable != null && viewClickable.length == size)
			for (int i = size - 1; i >= 0; i--)
				mItemMappings[i].clickable = viewClickable[i];

		if (defaultResources != null && defaultResources.length == size)
			for (int i = size - 1; i >= 0; i--)
				mItemMappings[i].defaultResource = defaultResources[i];

	}

	private void generateCache() {

		Log.d(LOG_TAG, "regenerate cache");

		if (mItemMappings == null)
			return;
		final int size = mItemMappings.length;

		Cursor cursor = mContentResolver.query(Uri.parse(mIntent
				.getStringExtra(LauncherIntent.Extra.Scroll.EXTRA_DATA_URI)), mIntent
				.getStringArrayExtra(LauncherIntent.Extra.Scroll.EXTRA_PROJECTION), mIntent
				.getStringExtra(LauncherIntent.Extra.Scroll.EXTRA_SELECTION), mIntent
				.getStringArrayExtra(LauncherIntent.Extra.Scroll.EXTRA_SELECTION_ARGUMENTS), mIntent
				.getStringExtra(LauncherIntent.Extra.Scroll.EXTRA_SORT_ORDER));

		rowsElementsList.clear();

		while ((cursor != null) && (cursor.moveToNext())) {

			RowElementsList singleRowElem = new RowElementsList();

			ItemMapping itemMapping;
			try {
				// bind children views
				for (int i = size - 1; i >= 0; i--) {

					RowElement re = new RowElement();

					itemMapping = mItemMappings[i];

					switch (itemMapping.type) {
					case LauncherIntent.Extra.Scroll.Types.TEXTVIEW:
					case LauncherIntent.Extra.Scroll.Types.TEXTVIEWHTML:
						re.text = cursor.getString(itemMapping.index);
						break;
					case LauncherIntent.Extra.Scroll.Types.IMAGEBLOB:
						re.imageBlobData = cursor.getBlob(itemMapping.index);
						break;
					case LauncherIntent.Extra.Scroll.Types.IMAGEURI:
						re.imageUri = cursor.getString(itemMapping.index);
						break;
					case LauncherIntent.Extra.Scroll.Types.IMAGERESOURCE:
						re.imageResId = cursor.getInt(itemMapping.index);
						break;
					}

					// Prepare tag
					if (mItemChildrenClickable && itemMapping.clickable) {
						if (mItemActionUriIndex >= 0)
							re.tag = cursor.getString(mItemActionUriIndex);
						else
							re.tag = Integer.toString(cursor.getPosition());
					} else {
						if (mItemActionUriIndex >= 0) {
							re.tag = cursor.getString(mItemActionUriIndex);
						}
					}

					singleRowElem.singleRowElementsList.put(itemMapping.layoutId, re);

				}

				rowsElementsList.add(singleRowElem);

			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		if (cursor != null)
			cursor.close();

	}

	public void bindView(View view, Context context, int itemPosition) {
		if (mItemMappings == null)
			return;
		final int size = mItemMappings.length;

		ItemMapping itemMapping;
		View child;
		ImageView iv;
		try {
			// bind children views
			for (int i = size - 1; i >= 0; i--) {
				itemMapping = mItemMappings[i];

				child = view.findViewById(itemMapping.layoutId);

				RowElement rowElement = rowsElementsList.get(itemPosition).singleRowElementsList
						.get(itemMapping.layoutId);

				switch (itemMapping.type) {
				case LauncherIntent.Extra.Scroll.Types.TEXTVIEW:
					if (!(child instanceof TextView))
						break;
					// String text = cursor.getString(itemMapping.index);
					String text = rowElement.text;
					if (text != null)
						((TextView) child).setText(text);
					else
						((TextView) child).setText(itemMapping.defaultResource);
					break;
				case LauncherIntent.Extra.Scroll.Types.TEXTVIEWHTML:
					if (!(child instanceof TextView))
						break;
					// String textHtml = cursor.getString(itemMapping.index);
					String textHtml = rowElement.text;
					if (textHtml != null)
						((TextView) child).setText(Html.fromHtml(textHtml));
					else
						((TextView) child).setText(itemMapping.defaultResource);
					break;
				case LauncherIntent.Extra.Scroll.Types.IMAGEBLOB:
					if (!(child instanceof ImageView))
						break;
					iv = (ImageView) child;
					// byte[] data = cursor.getBlob(itemMapping.index);
					byte[] data = rowElement.imageBlobData;
					if (data != null)
						iv.setImageBitmap(BitmapFactory.decodeByteArray(data, 0, data.length));
					else if (itemMapping.defaultResource > 0)
						iv.setImageResource(itemMapping.defaultResource);
					else
						iv.setImageDrawable(null);
					break;
				case LauncherIntent.Extra.Scroll.Types.IMAGEURI:
					if (!(child instanceof ImageView))
						break;
					iv = (ImageView) child;
					// String uriStr = cursor.getString(itemMapping.index);
					String uriStr = rowElement.imageUri;
					if ((uriStr != null) && (!uriStr.equals("")))
						iv.setImageURI(Uri.parse(uriStr));
					else
						iv.setImageDrawable(null);
					break;
				case LauncherIntent.Extra.Scroll.Types.IMAGERESOURCE:
					if (!(child instanceof ImageView))
						break;
					iv = (ImageView) child;
					// int res = cursor.getInt(itemMapping.index);
					int res = rowElement.imageResId;
					if (res > 0)
						iv.setImageResource(res);
					else if (itemMapping.defaultResource > 0)
						iv.setImageResource(itemMapping.defaultResource);
					else
						iv.setImageDrawable(null);
					break;
				}

				// Prepare tag
				view.setTag(null);
				if (mItemChildrenClickable && itemMapping.clickable) {
					child.setTag(rowElement.tag);
					child.setOnClickListener(new ItemViewClickListener());
				} else {
					if (mItemActionUriIndex >= 0) {
						view.setTag(rowElement.tag);
					}
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	public View newView(Context context, Cursor c, ViewGroup parent) {
		return mInflater.inflate(mItemLayoutId, parent, false);
	}

	class ItemViewClickListener implements OnClickListener {

		public void onClick(View v) {
			try {
				String pos = (String) v.getTag();
				Intent intent = new Intent(LauncherIntent.Action.ACTION_VIEW_CLICK);
				intent.setComponent(mAppWidgetProvider);
				intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
				intent.putExtra(LauncherIntent.Extra.EXTRA_VIEW_ID, v.getId());
				intent.putExtra(LauncherIntent.Extra.Scroll.EXTRA_LISTVIEW_ID, mListViewId);
				intent.putExtra(LauncherIntent.Extra.Scroll.EXTRA_ITEM_POS, pos);
				v.getContext().sendBroadcast(intent);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

	}

	@Override
	public int getCount() {
		return rowsElementsList.size();
	}

	@Override
	public Object getItem(int position) {
		return position;
	}

	@Override
	public long getItemId(int position) {
		return position;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {

		if (convertView == null) {
			convertView = mInflater.inflate(mItemLayoutId, null);
		}

		if (position < getCount())
			bindView(convertView, convertView.getContext(), position);

		return convertView;

	}

	public void notifyToRegenerate() {
		if (LOGD)
			Log.d(LOG_TAG, "notifyToRegenerate widgetId = " + mAppWidgetId);

		if (mRegenerateCacheThread == null)
			mRegenerateCacheThread = new RegenerateCacheThread();
		mRegenerateCacheThread.run();
	}
}
