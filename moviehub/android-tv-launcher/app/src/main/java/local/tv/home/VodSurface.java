package local.tv.home;

import android.graphics.SurfaceTexture;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Own detached VOD textures until the native decoder has stopped using their buffers. Main thread only. */
final class VodSurface implements TextureView.SurfaceTextureListener {

    private final TextureView.SurfaceTextureListener delegate;
    private final Set<SurfaceTexture> retained = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean stopped;

    private VodSurface(TextureView texture) {
        delegate = texture.getSurfaceTextureListener();
        texture.setSurfaceTextureListener(this);
    }

    static VodSurface retain(View root) {
        TextureView texture = texture(root);
        return texture == null ? null : new VodSurface(texture);
    }

    static TextureView texture(View root) {
        if (root instanceof TextureView) return (TextureView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextureView texture = texture(group.getChildAt(i));
                if (texture != null) return texture;
            }
        }
        return null;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (delegate != null) delegate.onSurfaceTextureAvailable(surface, width, height);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        if (delegate != null) delegate.onSurfaceTextureSizeChanged(surface, width, height);
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        if (delegate != null) delegate.onSurfaceTextureUpdated(surface);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (stopped) return true;
        // Do not call VLC's listener here: it detaches the native window synchronously.
        // Returning false also prevents Android from releasing the buffer queue during stop().
        retained.add(surface);
        return false;
    }

    /** Called after native stop and detachViews; still-attached textures stay owned by Android. */
    void stopped() {
        stopped = true;
        for (SurfaceTexture surface : retained) surface.release();
        retained.clear();
    }
}
