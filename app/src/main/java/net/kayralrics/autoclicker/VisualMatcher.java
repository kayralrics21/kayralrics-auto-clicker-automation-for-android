package net.kayralrics.autoclicker;

import android.graphics.Bitmap;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/** OpenCV's normalized correlation is the same method as the Fedora version. */
final class VisualMatcher {
    static final class Match {
        final String name;
        final int x, y, nextIndex;
        final double score;
        Match(String name, int x, int y, int nextIndex, double score) {
            this.name = name; this.x = x; this.y = y;
            this.nextIndex = nextIndex; this.score = score;
        }
    }

    private static final class Prepared {
        final TargetStore.Target metadata;
        final Mat gray;
        Prepared(TargetStore.Target metadata, Mat gray) {
            this.metadata = metadata; this.gray = gray;
        }
    }

    private final List<Prepared> prepared = new ArrayList<>();

    void reload(TargetStore store) {
        clear();
        for (TargetStore.Target target : store.read().targets) {
            if (!target.enabled) continue;
            Bitmap b = store.load(target.id);
            if (b == null) continue;
            Mat rgba = new Mat(), gray = new Mat();
            try {
                Utils.bitmapToMat(b, rgba);
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
                if (gray.cols() >= 12 && gray.rows() >= 12) {
                    prepared.add(new Prepared(target, gray));
                    gray = null;
                }
            } finally {
                rgba.release();
                if (gray != null) gray.release();
                b.recycle();
            }
        }
    }

    Match findOne(Bitmap screenshot, double minScore, int fromIndex) {
        if (prepared.isEmpty()) return null;
        Mat rgba = new Mat(), frame = new Mat();
        try {
            Utils.bitmapToMat(screenshot, rgba);
            Imgproc.cvtColor(rgba, frame, Imgproc.COLOR_RGBA2GRAY);
            int count = prepared.size();
            for (int offset = 0; offset < count; offset++) {
                int index = (fromIndex + offset) % count;
                Prepared p = prepared.get(index);
                if (p.gray.cols() > frame.cols() || p.gray.rows() > frame.rows()) continue;
                Mat scores = new Mat();
                try {
                    Imgproc.matchTemplate(frame, p.gray, scores, Imgproc.TM_CCOEFF_NORMED);
                    Core.MinMaxLocResult result = Core.minMaxLoc(scores);
                    if (Double.isFinite(result.maxVal) && result.maxVal >= minScore) {
                        Point top = result.maxLoc;
                        int x = (int) (top.x + p.gray.cols() / 2.0);
                        int y = (int) (top.y + p.gray.rows() / 2.0);
                        return new Match(p.metadata.name, x, y, (index + 1) % count,
                            result.maxVal);
                    }
                } finally {
                    scores.release();
                }
            }
            return null;
        } finally {
            rgba.release(); frame.release();
        }
    }

    void clear() {
        for (Prepared target : prepared) target.gray.release();
        prepared.clear();
    }
}
