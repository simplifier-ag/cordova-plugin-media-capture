package org.apache.cordova.mediacapture;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.text.Html;
import android.text.Spanned;
import android.util.TypedValue;

public class R {
	/**
	 * @param filename     Name of the file
	 * @param resourceType Type of resource (ID, STRING, LAYOUT, DRAWABLE)
	 * @return The associated resource identifier. Returns 0 if no such resource was found. (0 is not a valid resource ID.)
	 */
	private static int getResourceId(Context context, String filename, String resourceType) {
		String package_name = context.getPackageName();
		Resources resources = context.getResources();

		return resources.getIdentifier(filename, resourceType, package_name);
	}

	/**
	 * @param identifier identifier of the string
	 * @return The localized string. Returns identifier if no such resource was found.
	 */
	public static String localize(Context context, String identifier, Object... args) {
		int id = getResourceId(context, identifier, ResourceTypes.STRING);

		if (id == 0) {
			return identifier;
		} else {
			return args.length > 0
					? String.format(context.getResources().getString(id), args)
					: context.getResources().getString(id);
		}
	}

	/**
	 * Fetches a translated string by identifier. Use this method if the fetched string contains HTML tags.
	 *
	 * @param context    Context
	 * @param identifier identifier of the String
	 * @param args
	 * @return Formatted Spanned
	 */
	public static Spanned localizeHTML(Context context, String identifier, Object... args) {
		String formattedString = localize(context, identifier, args);
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
				? Html.fromHtml(formattedString, Html.FROM_HTML_MODE_COMPACT)
				: Html.fromHtml(formattedString);
	}

	/**
	 * @param identifier identifier of the drawable
	 * @return Id of the drawable. Returns 0 if no such resource was found.
	 */
	public static int getDrawable(Context context, String identifier) {
		return getResourceId(context, identifier, ResourceTypes.DRAWABLE);
	}

	/**
	 * @param identifier identifier of the layout
	 * @return Id of the layout. Returns 0 if no such resource was found.
	 */
	public static int getLayout(Context context, String identifier) {
		return getResourceId(context, identifier, ResourceTypes.LAYOUT);
	}

	/**
	 * Returns the Id of a view
	 *
	 * @param identifier identifier of the id
	 * @return Id of the view. Returns 0 if no such resource was found.
	 */
	public static int getId(Context context, String identifier) {
		return getResourceId(context, identifier, ResourceTypes.ID);
	}

	public static int getXml(Context context, String identifier) {
		return getResourceId(context, identifier, ResourceTypes.XML);
	}

	public static int getStyle(Context context, String identifier) {
		return getResourceId(context, identifier, ResourceTypes.STYLE);
	}

	public static int getAnimation(Context context, String identifier) {
		return getResourceId(context, identifier, ResourceTypes.ANIMATION);
	}

	public static int getRaw(Context context, String identifier) {
		return getResourceId(context, identifier, ResourceTypes.RAW);
	}

	public static int getMipmap(Context context, String identifier) {
		return getResourceId(context, identifier, ResourceTypes.MIPMAP);
	}

	public static int getColorId(Context context, String identifier) {
		return getResourceId(context, identifier, ResourceTypes.COLOR);
	}

	public static int getAttributeId(Context context, String identifier) {
		return getResourceId(context, identifier, ResourceTypes.ATTR);
	}

	public static int getThemeColor(Context context, String identifier) {
		TypedValue typedValue = new TypedValue();
		context.getTheme().resolveAttribute(getAttributeId(context, identifier), typedValue, true);
		return typedValue.data;
	}

	public static int getMenu(Context context, String identifier) {
		return getResourceId(context, identifier, ResourceTypes.MENU);
	}

	public static class ResourceTypes {
		static String LAYOUT = "layout";
		static String STRING = "string";
		static String ID = "id";
		static String DRAWABLE = "drawable";
		static String XML = "xml";
		static String STYLE = "style";
		static String ANIMATION = "anim";
		static String RAW = "raw";
		static String MIPMAP = "mipmap";
		static String COLOR = "color";
		static String ATTR = "attr";
		static String MENU = "menu";
	}
}

