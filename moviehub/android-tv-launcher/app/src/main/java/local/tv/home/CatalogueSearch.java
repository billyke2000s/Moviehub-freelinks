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

/** Title, person and company search with recent-search history. */
final class CatalogueSearch {

    private final MainActivity activity;

    CatalogueSearch(MainActivity activity) {
        this.activity = activity;
    }

    void entitySearch(LinearLayout target, String kind, String section) {
        String type = kind.equals("Movies") ? "movie" : "tv",
            entity = section.equals(":people")
                ? "person"
                : section.equals(":companies")
                  ? "company"
                  : section.equals(":keywords")
                    ? "keyword"
                    : "collection";
        EditText query = activity.field(
            target,
            section.equals(":people")
                ? "Actor, director or creator"
                : section.equals(":companies")
                  ? "Studio name"
                  : section.equals(":keywords")
                    ? "Keyword or theme"
                    : "Collection name",
            false,
            ""
        );
        LinearLayout found = activity.vertical();
        target.addView(
            activity.button("Search", () -> {
                String value = query.getText().toString().trim();
                if (value.isEmpty()) {
                    query.setError("Enter a search term");
                    return;
                }
                if (!activity.secrets.has("tmdb")) {
                    activity.settingsScreens.accountScreen("TMDB");
                    return;
                }
                entityResults(found, kind, entity, value, 1);
            })
        );
        target.addView(found);
    }

    private void entityResults(LinearLayout target, String kind, String entity, String query, int number) {
        activity.generation++;
        String type = kind.equals("Movies") ? "movie" : "tv";
        target.removeAllViews();
        TextView loading = activity.text("Loading matches…", 15);
        target.addView(loading);
        activity.async(
            () ->
                activity.media.tmdb(
                    "search/" +
                        entity +
                        "?query=" +
                        Network.q(query) +
                        "&page=" +
                        number +
                        (entity.equals("person") || entity.equals("collection") ? "&include_adult=false" : "")
                ),
            response -> {
                target.removeAllViews();
                JSONArray items = response.getJSONArray("results");
                List<TmdbCategories.Entry> choices = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    if (item.optBoolean("adult", false)) continue;
                    int id = item.getInt("id");
                    String route = entity.equals("collection")
                        ? "collection/" + id
                        : entity.equals("person")
                          ? "person/" + id + "/" + type + "_credits"
                          : TmdbCategories.discover(
                                type,
                                (entity.equals("company") ? "with_companies=" : "with_keywords=") + id
                            );
                    choices.add(new TmdbCategories.Entry(item.getString("name"), route));
                }
                activity.catalogues.options.categoryChoices(target, kind, choices);
                if (choices.isEmpty()) target.addView(activity.text("No matches found", 16));
                LinearLayout pages = activity.row();
                if (number > 1) pages.addView(
                    activity.button("Previous", () -> entityResults(target, kind, entity, query, number - 1))
                );
                if (number < Math.min(response.optInt("total_pages", 1), 500)) pages.addView(
                    activity.button("Next", () -> entityResults(target, kind, entity, query, number + 1))
                );
                target.addView(pages);
            }
        );
    }

    private JSONArray recentSearches() {
        try {
            return new JSONArray(activity.prefs.getString("searchHistory", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    void rememberSearch(String value) {
        JSONArray next = new JSONArray();
        next.put(value);
        JSONArray old = recentSearches();
        for (int i = 0; i < old.length() && next.length() < 12; i++) if (
            !value.equalsIgnoreCase(old.optString(i))
        ) next.put(old.optString(i));
        activity.prefs.edit().putString("searchHistory", next.toString()).apply();
    }

    /** Test hook: when set, search requests are answered from this instead of TMDB. */
    static java.util.function.Function<String, JSONObject> testSource;

    /** Waits this long after the last key press before searching, so typing stays smooth. */
    static final long TYPING_PAUSE_MILLIS = 450;

    void searchCatalogue(String kind) {
        searchCatalogue(kind, "");
    }

    /**
     * Streaming-app style search: one large search box, results that appear as you type, shown as
     * Movies, Series and People rows on this page. Recent searches appear while the box is empty.
     */
    void searchCatalogue(String kind, String initial) {
        activity.screen("Search", () -> activity.libraryScreens.library(kind));
        EditText query = activity.searchBox(activity.page, "Search movies, series and people");
        query.setTextSize(22);
        query.setPadding(activity.dp(24), activity.dp(14), activity.dp(24), activity.dp(14));
        query.setText(initial);
        LinearLayout results = activity.vertical();
        results.setClipChildren(false);
        activity.page.addView(results, new LinearLayout.LayoutParams(-1, -2));

        Search search = new Search(kind, query, results);
        query.addTextChangedListener(
            new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                public void afterTextChanged(android.text.Editable text) {
                    search.typed(text.toString().trim());
                }
            }
        );
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            String value = query.getText().toString().trim();
            if (!value.isEmpty()) {
                rememberSearch(value);
                search.run(value);
            }
            return true;
        });
        if (initial.isEmpty()) search.showRecent();
        else search.run(initial);
        query.requestFocus();
    }

    /** One search page: debounced typing, result rows and recent searches. */
    final class Search {

        final String kind;
        final EditText query;
        final LinearLayout results;
        final List<PosterFeed> feeds = new ArrayList<>();
        private final Runnable pending = () -> run(currentText());
        private String shown = "";
        private int token;
        private int emptyRows;

        Search(String kind, EditText query, LinearLayout results) {
            this.kind = kind;
            this.query = query;
            this.results = results;
        }

        private String currentText() {
            return query.getText().toString().trim();
        }

        void typed(String value) {
            activity.handler.removeCallbacks(pending);
            if (value.isEmpty()) {
                shown = "";
                showRecent();
            } else if (value.length() >= 2) activity.handler.postDelayed(pending, TYPING_PAUSE_MILLIS);
        }

        private void clear() {
            token++;
            for (PosterFeed feed : feeds) {
                feed.close();
                activity.feeds.remove(feed);
            }
            feeds.clear();
            results.removeAllViews();
        }

        void showRecent() {
            clear();
            JSONArray history = recentSearches();
            if (history.length() == 0) {
                results.addView(heading("Search by title, actor or director"));
                return;
            }
            results.addView(heading("Recent searches"));
            HorizontalScrollView scroll = new HorizontalScrollView(activity);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipToPadding(false);
            LinearLayout pills = activity.row();
            pills.setClipChildren(false);
            pills.setPadding(activity.dp(4), activity.dp(6), activity.dp(24), activity.dp(6));
            scroll.addView(pills);
            results.addView(scroll);
            for (int i = 0; i < history.length(); i++) {
                String value = history.optString(i);
                recentButton(pills, value, () -> query.setText(value));
            }
            recentButton(pills, "Clear", () -> {
                activity.prefs.edit().remove("searchHistory").apply();
                showRecent();
            });
        }

        private void recentButton(LinearLayout parent, String label, Runnable run) {
            Button button = activity.button(label, run);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, activity.dp(38));
            lp.rightMargin = activity.dp(10);
            parent.addView(button, lp);
        }

        private TextView heading(String text) {
            TextView title = activity.compactText(text, 18);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setPadding(activity.dp(4), activity.dp(18), 0, activity.dp(4));
            return title;
        }

        void run(String value) {
            if (value.equals(shown) && results.getChildCount() > 0) return;
            shown = value;
            clear();
            activity.destination = () -> searchCatalogue(kind, value);
            if (testSource == null && !activity.secrets.has("tmdb")) {
                activity.emptyState(
                    results,
                    "Connect TMDB",
                    "Search uses TMDB. Add your free API key to search movies, series and people.",
                    "Connect TMDB",
                    () -> activity.settingsScreens.accountScreen("TMDB")
                );
                return;
            }
            emptyRows = 0;
            String encoded = Network.q(value);
            boolean moviesFirst = kind.equals("Movies");
            titleRow(moviesFirst ? "Movies" : "Series", moviesFirst ? "movie" : "tv", encoded);
            titleRow(moviesFirst ? "Series" : "Movies", moviesFirst ? "tv" : "movie", encoded);
            peopleRow(value, encoded);
        }

        private JSONObject fetch(String path) throws Exception {
            return testSource != null ? testSource.apply(path) : activity.media.tmdb(path);
        }

        private void rowEmpty(LinearLayout section, int request) {
            activity.runOnUiThread(() -> {
                if (request != token) return;
                section.setVisibility(View.GONE);
                if (++emptyRows == 3) results.addView(heading("No results for \u201c" + shown + "\u201d"));
            });
        }

        private void titleRow(String name, String type, String encoded) {
            int request = token;
            LinearLayout section = activity.vertical();
            section.addView(heading(name));
            PosterFeed feed = new PosterFeed(
                activity,
                true,
                type,
                activity.io,
                n -> {
                    JSONObject page = fetch(
                        "search/" + type + "?query=" + encoded + "&include_adult=false&page=" + n
                    );
                    JSONArray items = page.optJSONArray("results");
                    if (n == 1 && (items == null || items.length() == 0)) rowEmpty(section, request);
                    return page;
                },
                new PosterFeed.Cards() {
                    public View create(ViewGroup parent) {
                        View card = activity.posters.posterCard(true);
                        int frame = activity.dp(3);
                        card.setPadding(frame, frame, frame, frame);
                        return card;
                    }

                    public void bind(View card, JSONObject item) {
                        activity.posters.bindPoster(card, item, true, () -> rememberSearch(shown));
                    }
                }
            );
            feed.setCardSize(CatalogueRows.POSTER_WIDTH, CatalogueRows.POSTER_HEIGHT);
            feed.setClipToPadding(false);
            feed.setPadding(0, activity.dp(4), activity.dp(24), activity.dp(4));
            feeds.add(feed);
            activity.feeds.add(feed);
            section.addView(
                feed,
                new LinearLayout.LayoutParams(-1, activity.dp(CatalogueRows.POSTER_HEIGHT + 16))
            );
            results.addView(section);
            feed.start();
        }

        private void peopleRow(String value, String encoded) {
            int request = token;
            LinearLayout section = activity.vertical();
            section.addView(heading("People"));
            HorizontalScrollView scroll = new HorizontalScrollView(activity);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipToPadding(false);
            LinearLayout people = activity.row();
            people.setClipChildren(false);
            people.setPadding(0, activity.dp(6), activity.dp(24), activity.dp(6));
            scroll.addView(people);
            section.addView(scroll);
            results.addView(section);
            String type = kind.equals("Movies") ? "movie" : "tv";
            activity.io.execute(() -> {
                JSONArray found;
                try {
                    found = fetch(
                        "search/person?query=" + encoded + "&include_adult=false&page=1"
                    ).optJSONArray("results");
                } catch (Exception e) {
                    Diagnostics.recovered("Searching people", e);
                    found = null;
                }
                JSONArray list = found;
                activity.runOnUiThread(() -> {
                    if (request != token) return;
                    if (list == null || list.length() == 0) {
                        rowEmpty(section, request);
                        return;
                    }
                    for (int i = 0; i < Math.min(20, list.length()); i++) {
                        JSONObject person = list.optJSONObject(i);
                        if (person == null || person.optBoolean("adult", false)) continue;
                        people.addView(
                            personCard(person, () -> {
                                rememberSearch(value);
                                activity.catalogues.browse(
                                    kind,
                                    person.optString("name"),
                                    "person/" + person.optInt("id") + "/" + type + "_credits",
                                    1
                                );
                            })
                        );
                    }
                });
            });
        }
    }

    /** A round photo with a name, as used for cast and people in search. */
    View personCard(JSONObject person, Runnable open) {
        LinearLayout card = activity.vertical();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        ImageView photo = new ImageView(activity);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setClipToOutline(true);
        photo.setOutlineProvider(
            new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            }
        );
        card.addView(photo, new LinearLayout.LayoutParams(activity.dp(92), activity.dp(92)));
        String image = person.optString("profile_path");
        if (image.startsWith("/")) activity.posters.loadImage(
            photo,
            "https://image.tmdb.org/t/p/w185" + image
        );
        TextView name = activity.compactText(person.optString("name"), 13);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(name, new LinearLayout.LayoutParams(-1, -2));
        String role = person.optString("character", person.optString("known_for_department"));
        TextView detail = activity.muted(role, 11);
        detail.setGravity(Gravity.CENTER);
        detail.setMaxLines(1);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        card.setContentDescription(person.optString("name"));
        ringPhoto(photo, false);
        card.setOnFocusChangeListener((v, focused) -> {
            ringPhoto(photo, focused);
            v.setScaleX(focused ? 1.08f : 1f);
            v.setScaleY(focused ? 1.08f : 1f);
            name.setTypeface(Typeface.DEFAULT, focused ? Typeface.BOLD : Typeface.NORMAL);
        });
        card.setOnClickListener(v -> open.run());
        card.setLayoutParams(cardLayout());
        return card;
    }

    private LinearLayout.LayoutParams cardLayout() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(activity.dp(116), -2);
        lp.rightMargin = activity.dp(10);
        return lp;
    }

    private void ringPhoto(ImageView photo, boolean focused) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0xff1b2433);
        if (focused) ring.setStroke(activity.dp(3), 0xfff2f4f8);
        photo.setBackground(ring);
        int inset = focused ? activity.dp(3) : 0;
        photo.setPadding(inset, inset, inset, inset);
    }
}
