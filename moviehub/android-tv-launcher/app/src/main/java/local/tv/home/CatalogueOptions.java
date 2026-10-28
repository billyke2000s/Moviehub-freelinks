package local.tv.home;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/**
 * The Browse and Filters sheets of the Movies/Series screen: category and genre lists, region,
 * years, languages, countries, streaming services and the combined "Refine results" form.
 * Split out of {@link CatalogueScreens}; no behaviour of its own beyond those sheets.
 */
final class CatalogueOptions {

    private final MainActivity activity;
    private final CatalogueScreens screens;

    CatalogueOptions(MainActivity activity, CatalogueScreens screens) {
        this.activity = activity;
        this.screens = screens;
    }

    String catalogueRegion() {
        String fallback = Locale.getDefault().getCountry();
        return activity.prefs.getString("catalogueRegion", fallback.matches("[A-Z]{2}") ? fallback : "GB");
    }

    void categoryChoices(LinearLayout target, String kind, List<TmdbCategories.Entry> entries) {
        LinearLayout line = null;
        for (int i = 0; i < entries.size(); i++) {
            TmdbCategories.Entry entry = entries.get(i);
            if (i % 2 == 0) {
                line = activity.row();
                target.addView(line);
            }
            Button choice = activity.button(entry.name, () ->
                screens.browse(kind, entry.name, entry.path, 1)
            );
            choice.setTextSize(14);
            choice.setTag(entry.path);
            choice.setContentDescription(entry.name);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, activity.dp(42), 1);
            lp.setMargins(activity.dp(3), activity.dp(3), activity.dp(3), activity.dp(3));
            line.addView(choice, lp);
        }
        if (line != null) while (line.getChildCount() < 2)
            line.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
    }

    void renderCategoryHub(LinearLayout target, String kind, String section) {
        String type = kind.equals("Movies") ? "movie" : "tv";
        target.removeAllViews();
        if (!section.equals(":filters")) {
            target.addView(activity.compactText("Genres", 18));
            categoryChoices(target, kind, screens.catalogueGenres(type));
        }
        if (section.equals(":categories")) {
            target.addView(activity.text("Popular & new", 18));
            categoryChoices(target, kind, TmdbCategories.lists(type));
        }
        if (!section.equals(":genres")) {
            target.addView(activity.text("Browse by", 18));
            categoryChoices(target, kind, TmdbCategories.groups(type));
        }
    }

    void refreshCatalogueGenres(LinearLayout target, String kind, String section) {
        if (!activity.secrets.has("tmdb")) return;
        int request = activity.generation;
        String type = kind.equals("Movies") ? "movie" : "tv";
        activity.io.execute(() -> {
            try {
                JSONArray result = activity.media.tmdb("genre/" + type + "/list").getJSONArray("genres");
                for (int i = 0; i < result.length(); i++) {
                    JSONObject item = result.getJSONObject(i);
                    TmdbCategories.genre(type, item.getInt("id"), item.getString("name"));
                }
                String data = result.toString();
                if (data.equals(activity.prefs.getString("catalogueGenres_" + type, "[]"))) return;
                activity.prefs
                    .edit()
                    .putString("catalogueGenres_" + type, data)
                    .apply();
                activity.runOnUiThread(() -> {
                    if (
                        activity.isFinishing() || activity.isDestroyed() || activity.generation != request
                    ) return;
                    View focused = activity.getCurrentFocus();
                    Object route = focused == null ? null : focused.getTag();
                    boolean inside = false;
                    for (
                        android.view.ViewParent parent = focused == null ? null : focused.getParent();
                        parent != null;
                        parent = parent.getParent()
                    ) if (parent == target) {
                        inside = true;
                        break;
                    }
                    renderCategoryHub(target, kind, section);
                    if (inside && route != null) {
                        View replacement = target.findViewWithTag(route);
                        if (replacement != null) replacement.requestFocus();
                    }
                });
            } catch (Exception e) {
                Diagnostics.recovered("Refreshing catalogue genres", e);
                /* Official built-in and previously fetched genres remain usable offline. */
            }
        });
    }

    void renderCatalogueOptions(LinearLayout grid, String kind, String path) {
        String type = kind.equals("Movies") ? "movie" : "tv";
        if (path.equals(":filters")) {
            grid.addView(activity.button("Refine results", () -> refineCatalogue(kind)));
            categoryChoices(grid, kind, TmdbCategories.groups(type));
            return;
        }
        if (path.equals(":categories") || path.equals(":genres")) {
            renderCategoryHub(grid, kind, path);
            refreshCatalogueGenres(grid, kind, path);
            return;
        }
        if (path.equals(":region")) {
            grid.addView(activity.text("Loading countries…", 16));
            activity.async(
                () ->
                    new JSONArray(
                        Network.text(
                            MediaRepository.tmdbUrl("configuration/countries", activity.secrets.get("tmdb")),
                            null,
                            false
                        )
                    ),
                countries -> {
                    grid.removeAllViews();
                    for (int i = 0; i < countries.length(); i++) {
                        JSONObject country = countries.getJSONObject(i);
                        String code = country.getString("iso_3166_1");
                        grid.addView(
                            activity.button(country.optString("english_name", code), () -> {
                                activity.prefs.edit().putString("catalogueRegion", code).apply();
                                if (screens.catalogue != null) {
                                    screens.catalogue.overlayPages.pop();
                                    screens.catalogue.renderOverlay();
                                }
                            })
                        );
                    }
                }
            );
            return;
        }
        if (path.equals(":years")) {
            grid.addView(
                activity.button("Choose a year", () -> {
                    LinearLayout content = activity.vertical();
                    EditText input = activity.field(
                        content,
                        "Year",
                        false,
                        String.valueOf(java.time.LocalDate.now().getYear())
                    );
                    input.setInputType(InputType.TYPE_CLASS_NUMBER);
                    Dialog dialog = activity.form("Browse by year", content);
                    content.addView(
                        activity.button("Browse", () -> {
                            try {
                                TmdbCategories.Entry entry = TmdbCategories.year(
                                    type,
                                    Integer.parseInt(input.getText().toString().trim())
                                );
                                dialog.dismiss();
                                screens.browse(kind, entry.name, entry.path, 1);
                            } catch (Exception e) {
                                input.setError("Enter a year from 1874 to 9999");
                            }
                        })
                    );
                })
            );
        }
        if (
            Arrays.asList(
                ":years",
                ":ratings",
                ":runtime",
                ":sort",
                ":types",
                ":status",
                ":releases",
                ":availability"
            ).contains(path)
        ) {
            categoryChoices(
                grid,
                kind,
                TmdbCategories.filters(type, path, catalogueRegion(), java.time.LocalDate.now())
            );
            return;
        }
        if (
            path.equals(":people") ||
            path.equals(":companies") ||
            path.equals(":keywords") ||
            path.equals(":collections")
        ) {
            activity.catalogueSearch.entitySearch(grid, kind, path);
            return;
        }
        if (!activity.secrets.has("tmdb")) {
            grid.addView(activity.text("Connect TMDB to browse this catalogue", 20));
            grid.addView(
                activity.button("Connect TMDB", () -> activity.settingsScreens.accountScreen("TMDB"))
            );
            return;
        }
        TextView loading = activity.text("Loading titles…", 15);
        grid.addView(loading);
        if (path.equals(":languages") || path.equals(":countries")) {
            String endpoint = path.equals(":languages")
                ? "configuration/languages"
                : "configuration/countries";
            activity.async(
                () ->
                    new JSONArray(
                        Network.text(
                            MediaRepository.tmdbUrl(endpoint, activity.secrets.get("tmdb")),
                            null,
                            false
                        )
                    ),
                items -> {
                    grid.removeAllViews();
                    List<TmdbCategories.Entry> choices = new ArrayList<>();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        boolean language = path.equals(":languages");
                        String code = item.optString(language ? "iso_639_1" : "iso_3166_1"),
                            name = item.optString("english_name", item.optString("name", code));
                        if (code.isEmpty()) continue;
                        choices.add(
                            new TmdbCategories.Entry(
                                name,
                                TmdbCategories.discover(
                                    type,
                                    (language ? "with_original_language=" : "with_origin_country=") +
                                        Network.q(code)
                                )
                            )
                        );
                    }
                    choices.sort(Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)));
                    categoryChoices(grid, kind, choices);
                }
            );
            return;
        }
        if (path.equals(":services")) {
            grid.removeAllViews();
            grid.addView(activity.button("Region: " + catalogueRegion(), () -> chooseCatalogueRegion(kind)));
            grid.addView(activity.compactText("Availability data: JustWatch", 12));
            categoryChoices(
                grid,
                kind,
                TmdbCategories.filters(type, ":availability", catalogueRegion(), java.time.LocalDate.now())
            );
            grid.addView(loading);
            activity.async(
                () ->
                    activity.media
                        .tmdb("watch/providers/" + type + "?watch_region=" + catalogueRegion())
                        .getJSONArray("results"),
                items -> {
                    grid.removeView(loading);
                    List<TmdbCategories.Entry> choices = new ArrayList<>();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject provider = items.getJSONObject(i);
                        choices.add(
                            new TmdbCategories.Entry(
                                provider.getString("provider_name"),
                                TmdbCategories.discover(
                                    type,
                                    "watch_region=" +
                                        catalogueRegion() +
                                        "&with_watch_providers=" +
                                        provider.getInt("provider_id")
                                )
                            )
                        );
                    }
                    if (choices.isEmpty()) grid.addView(
                        activity.text("No services listed in this region", 16)
                    );
                    categoryChoices(grid, kind, choices);
                }
            );
            return;
        }
    }

    void refineCatalogue(String kind) {
        String type = kind.equals("Movies") ? "movie" : "tv",
            prefix = "refine_" + type + "_";
        LinearLayout content = activity.vertical();
        List<TmdbCategories.Entry> genres = screens.catalogueGenres(type);
        int[] genre = { activity.prefs.getInt(prefix + "genre", 0) },
            rating = { activity.prefs.getInt(prefix + "rating", 0) },
            sort = { activity.prefs.getInt(prefix + "sort", 0) };
        String[] orders = { "Most popular", "Highest rated", "Newest releases", "Title A–Z" },
            routes = { "popularity.desc", "vote_average.desc", "date.desc", "title.asc" };
        sort[0] = Math.max(0, Math.min(orders.length - 1, sort[0]));
        String genreName = "Any genre";
        for (TmdbCategories.Entry entry : genres)
            if (entry.path.endsWith("=" + genre[0])) genreName = entry.name;
        Button genreButton = activity.button(genreName, () -> {});
        content.addView(activity.muted("Genre", 14));
        content.addView(genreButton);
        genreButton.setOnClickListener(v -> {
            String[] choices = new String[genres.size() + 1];
            choices[0] = "Any genre";
            for (int i = 0; i < genres.size(); i++) choices[i + 1] = genres.get(i).name;
            int current = 0;
            for (int i = 0; i < genres.size(); i++) if (genres.get(i).path.endsWith("=" + genre[0])) current =
                i + 1;
            SidePanel.of(activity, "Genre")
                .choices(choices, current, which -> {
                    genre[0] =
                        which == 0
                            ? 0
                            : Integer.parseInt(
                                  genres
                                      .get(which - 1)
                                      .path.substring(genres.get(which - 1).path.lastIndexOf('=') + 1)
                              );
                    genreButton.setText(choices[which]);
                })
                .show();
        });
        EditText year = activity.field(
            content,
            "Release year · leave blank for any",
            false,
            activity.prefs.getInt(prefix + "year", 0) == 0
                ? ""
                : String.valueOf(activity.prefs.getInt(prefix + "year", 0))
        );
        year.setInputType(InputType.TYPE_CLASS_NUMBER);
        content.addView(activity.muted("Minimum rating", 14));
        Button ratingButton = activity.button(rating[0] == 0 ? "Any rating" : rating[0] + "+", () -> {});
        content.addView(ratingButton);
        ratingButton.setOnClickListener(v ->
            SidePanel.of(activity, "Minimum rating")
                .choices(
                    new String[] { "Any rating", "5+", "6+", "7+", "8+", "9+" },
                    rating[0] == 0 ? 0 : rating[0] - 4,
                    which -> {
                        rating[0] = which == 0 ? 0 : which + 4;
                        ratingButton.setText(rating[0] == 0 ? "Any rating" : rating[0] + "+");
                    }
                )
                .show()
        );
        content.addView(activity.muted("Sort order", 14));
        Button sortButton = activity.button(orders[sort[0]], () -> {});
        content.addView(sortButton);
        sortButton.setOnClickListener(v ->
            SidePanel.of(activity, "Sort order")
                .choices(orders, sort[0], which -> {
                    sort[0] = which;
                    sortButton.setText(orders[which]);
                })
                .show()
        );
        Dialog dialog = activity.form("Refine " + kind.toLowerCase(Locale.ROOT), content);
        LinearLayout actions = activity.row();
        Button apply = activity.chip(actions, "Apply filters", () -> {
            try {
                int value = year.getText().toString().trim().isEmpty()
                    ? 0
                    : Integer.parseInt(year.getText().toString().trim());
                String route = TmdbCategories.refine(type, genre[0], value, rating[0], routes[sort[0]]);
                activity.prefs
                    .edit()
                    .putInt(prefix + "genre", genre[0])
                    .putInt(prefix + "year", value)
                    .putInt(prefix + "rating", rating[0])
                    .putInt(prefix + "sort", sort[0])
                    .apply();
                dialog.dismiss();
                screens.browse(kind, "Filtered results", route, 1);
            } catch (Exception e) {
                year.setError("Enter a year from 1874 to 9999");
            }
        });
        activity.primary(apply);
        activity.chip(actions, "Reset", () -> {
            genre[0] = 0;
            rating[0] = 0;
            sort[0] = 0;
            genreButton.setText("Any genre");
            ratingButton.setText("Any rating");
            sortButton.setText(orders[0]);
            year.setText("");
        });
        content.addView(actions);
    }

    void chooseCatalogueRegion(String kind) {
        screens.browse(kind, "Region", ":region", 1);
    }
}
