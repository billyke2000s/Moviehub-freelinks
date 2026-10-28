package local.tv.home;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.json.JSONException;

/**
 * Turns a failure into a short, plain explanation for the screen. Only the exception type and the
 * app's own fixed messages are used, never the exception text from elsewhere, because that can
 * contain provider addresses or account details.
 */
final class ErrorMessages {

    /** What to offer the user after the explanation. */
    enum Fix {
        RETRY,
        SETTINGS,
    }

    static final class Explanation {

        final String title, detail;
        final Fix fix;

        Explanation(String title, String detail, Fix fix) {
            this.title = title;
            this.detail = detail;
            this.fix = fix;
        }
    }

    private ErrorMessages() {}

    static Explanation explain(Throwable error) {
        String own = error == null || error.getMessage() == null ? "" : error.getMessage();
        if (own.equals("Connect TMDB first")) return new Explanation(
            "TMDB isn't connected",
            "Add your free TMDB API key in Settings to see movies and series.",
            Fix.SETTINGS
        );
        if (
            error instanceof UnknownHostException ||
            error instanceof ConnectException ||
            error instanceof NoRouteToHostException
        ) return new Explanation(
            "No internet connection",
            "Check the box is online, then try again.",
            Fix.RETRY
        );
        if (
            error instanceof SocketTimeoutException || error instanceof InterruptedIOException
        ) return new Explanation(
            "This is taking too long",
            "The service didn't answer in time. Try again in a moment.",
            Fix.RETRY
        );
        int status = status(own);
        if (status == 401 || status == 403) return new Explanation(
            "Account not accepted",
            "The service rejected your key or login. Check it in Settings.",
            Fix.SETTINGS
        );
        if (status == 404) return new Explanation(
            "Not found",
            "This is no longer available from the service.",
            Fix.RETRY
        );
        if (status == 429) return new Explanation(
            "Too many requests",
            "The service asked MovieHub to slow down. Wait a minute, then try again.",
            Fix.RETRY
        );
        if (status >= 500) return new Explanation(
            "The service is having problems",
            "It's not your connection or MovieHub. Try again later.",
            Fix.RETRY
        );
        if (error instanceof JSONException) return new Explanation(
            "Unexpected reply",
            "The service sent something MovieHub couldn't read. Try again later.",
            Fix.RETRY
        );
        return new Explanation(
            "Couldn't load this",
            "Check your connection and connected accounts, then try again.",
            Fix.RETRY
        );
    }

    /** The HTTP status from {@link Network}'s own "Server returned N" message, or 0. */
    static int status(String message) {
        if (!message.startsWith("Server returned ")) return 0;
        try {
            return Integer.parseInt(message.substring("Server returned ".length()).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
