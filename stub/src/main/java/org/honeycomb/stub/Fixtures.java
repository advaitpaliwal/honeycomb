package org.honeycomb.stub;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.util.SizeF;
import android.widget.RemoteViews;
import java.util.Map;

/**
 * Widget fixtures for WidgetFitTest and WidgetZooShots: one provider per shape a real widget comes in (Apple Music's
 * 1-row 240 x 30 dp, Gmail's 110 dp, 2 x 2, 4 x 2, 4 x 4, responsive RemoteViews with a size map, no background of its
 * own, not resizable). All but Responsive show their initialLayout and need no code; each is its own class only because
 * a manifest names every receiver once.
 */
public final class Fixtures {
    public static class Row extends AppWidgetProvider {}
    public static class Mail extends AppWidgetProvider {}
    public static class Cell2x2 extends AppWidgetProvider {}
    public static class Cell4x2 extends AppWidgetProvider {}
    public static class Cell4x4 extends AppWidgetProvider {}
    public static class Bare extends AppWidgetProvider {}
    public static class Fixed extends AppWidgetProvider {}

    /** Android 12's responsive layouts (developer.android.com/develop/ui/views/appwidgets/layouts#provide-responsive-layouts). */
    public static class Responsive extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
            RemoteViews small = new RemoteViews(c.getPackageName(), R.layout.fixture_responsive_small);
            RemoteViews large = new RemoteViews(c.getPackageName(), R.layout.fixture_responsive_large);
            for (int id : ids) m.updateAppWidget(id, new RemoteViews(Map.of(new SizeF(180f, 40f), small, new SizeF(250f, 110f), large)));
        }
    }
}
