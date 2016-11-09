/*
 * Copyright (c) 2011, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.prism.sw;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.Shape;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.marlin.IntArrayCache;
import com.sun.marlin.MarlinAlphaConsumer;
import com.sun.marlin.MarlinConst;
import com.sun.marlin.MarlinRenderer;
import com.sun.marlin.MarlinRenderingEngine;
import com.sun.marlin.RendererContext;
import com.sun.openpisces.Renderer;
import com.sun.pisces.PiscesRenderer;
import com.sun.prism.BasicStroke;
import com.sun.prism.PixelFormat;
import com.sun.prism.ResourceFactory;
import com.sun.prism.Texture;
import com.sun.prism.impl.PrismSettings;
import com.sun.prism.impl.shape.MarlinPrismUtils;
import com.sun.prism.impl.shape.MaskData;
import com.sun.prism.impl.shape.OpenPiscesPrismUtils;
import com.sun.prism.impl.shape.ShapeUtil;

import java.lang.ref.SoftReference;

final class SWContext {

    private final ResourceFactory factory;
    private final ShapeRenderer shapeRenderer;
    private SoftReference<SWRTTexture> readBackBufferRef;
    private SoftReference<SWArgbPreTexture> imagePaintTextureRef;

    interface ShapeRenderer {
        void renderShape(PiscesRenderer pr, Shape shape, BasicStroke stroke, BaseTransform tr, Rectangle clip, boolean antialiasedShape);
        void dispose();
    }

    class NativeShapeRenderer implements ShapeRenderer {
        private SoftReference<SWMaskTexture> maskTextureRef;

        public void renderShape(PiscesRenderer pr, Shape shape, BasicStroke stroke, BaseTransform tr, Rectangle clip, boolean antialiasedShape) {
            final MaskData mask = ShapeUtil.rasterizeShape(shape, stroke, clip.toRectBounds(), tr, true, antialiasedShape);
            final SWMaskTexture tex = this.validateMaskTexture(mask.getWidth(), mask.getHeight());
            mask.uploadToTexture(tex, 0, 0, false);
            pr.fillAlphaMask(tex.getDataNoClone(), mask.getOriginX(), mask.getOriginY(),
                             mask.getWidth(), mask.getHeight(), 0, tex.getPhysicalWidth());
        }

        private SWMaskTexture initMaskTexture(int width, int height) {
            final SWMaskTexture tex = (SWMaskTexture)factory.createMaskTexture(width, height, Texture.WrapMode.CLAMP_NOT_NEEDED);
            maskTextureRef = new SoftReference<SWMaskTexture>(tex);
            return tex;
        }

        private void disposeMaskTexture() {
            if (maskTextureRef != null){
                maskTextureRef.clear();
                maskTextureRef = null;
            }
        }

        private SWMaskTexture validateMaskTexture(int width, int height) {
            SWMaskTexture tex;
            if (maskTextureRef == null) {
                tex = this.initMaskTexture(width, height);
            } else {
                tex = maskTextureRef.get();
                if (tex == null ||
                    tex.getPhysicalWidth() < width ||
                    tex.getPhysicalHeight() < height)
                {
                    this.disposeMaskTexture();
                    tex = this.initMaskTexture(width, height);
                }
            }
            return tex;
        }

        public void dispose() {
            this.disposeMaskTexture();
        }
    }

    class JavaShapeRenderer implements ShapeRenderer {
        private final DirectRTPiscesAlphaConsumer alphaConsumer = new DirectRTPiscesAlphaConsumer();

        public void renderShape(PiscesRenderer pr, Shape shape, BasicStroke stroke, BaseTransform tr, Rectangle clip, boolean antialiasedShape) {
            if (stroke != null && stroke.getType() != BasicStroke.TYPE_CENTERED) {
                // RT-27427
                // TODO: Optimize the combinatorial strokes for simple
                // shapes and/or teach the rasterizer to be able to
                // do a "differential fill" between two shapes.
                // Note that most simple shapes will use a more optimized path
                // than this method for the INNER/OUTER strokes anyway.
                shape = stroke.createStrokedShape(shape);
                stroke = null;
            }
            final Renderer r = OpenPiscesPrismUtils.setupRenderer(shape, stroke, tr, clip, antialiasedShape);
            alphaConsumer.initConsumer(r, pr);
            r.produceAlphas(alphaConsumer);
        }

        public void dispose() { }
    }

    static final class MarlinShapeRenderer implements ShapeRenderer {
        private final DirectRTPiscesMarlinAlphaConsumer alphaConsumer = new DirectRTPiscesMarlinAlphaConsumer();

        @Override
        public void renderShape(PiscesRenderer pr, Shape shape, BasicStroke stroke, BaseTransform tr, Rectangle clip, boolean antialiasedShape) {
            if (stroke != null && stroke.getType() != BasicStroke.TYPE_CENTERED) {
                // RT-27427
                // TODO: Optimize the combinatorial strokes for simple
                // shapes and/or teach the rasterizer to be able to
                // do a "differential fill" between two shapes.
                // Note that most simple shapes will use a more optimized path
                // than this method for the INNER/OUTER strokes anyway.
                shape = stroke.createStrokedShape(shape);
                stroke = null;
            }
            final RendererContext rdrCtx = MarlinRenderingEngine.getRendererContext();
            MarlinRenderer renderer = null;
            try {
                renderer = MarlinPrismUtils.setupRenderer(rdrCtx, shape, stroke, tr, clip, antialiasedShape);
                final int outpix_xmin = renderer.getOutpixMinX();
                final int outpix_xmax = renderer.getOutpixMaxX();
                final int outpix_ymin = renderer.getOutpixMinY();
                final int outpix_ymax = renderer.getOutpixMaxY();
                final int w = outpix_xmax - outpix_xmin;
                final int h = outpix_ymax - outpix_ymin;
                if ((w <= 0) || (h <= 0)) {
                    return;
                }
                alphaConsumer.initConsumer(outpix_xmin, outpix_ymin, w, h, pr);
                renderer.produceAlphas(alphaConsumer);
            } finally {
                if (renderer != null) {
                    renderer.dispose();
                }
                // recycle the RendererContext instance
                MarlinRenderingEngine.returnRendererContext(rdrCtx);
            }
        }

        @Override
        public void dispose() { }

        private static final class DirectRTPiscesMarlinAlphaConsumer implements MarlinAlphaConsumer {
            private byte alpha_map[];
            private int x;
            private int y;
            private int w;
            private int h;
            private int rowNum;

            private PiscesRenderer pr;

            public void initConsumer(int x, int y, int w, int h, PiscesRenderer pr) {
                this.x = x;
                this.y = y;
                this.w = w;
                this.h = h;
                rowNum = 0;
                this.pr = pr;
            }

            @Override
            public int getOriginX() {
                return x;
            }

            @Override
            public int getOriginY() {
                return y;
            }

            @Override
            public int getWidth() {
                return w;
            }

            @Override
            public int getHeight() {
                return h;
            }

            @Override
            public void setMaxAlpha(int maxalpha) {
                if ((alpha_map == null) || (alpha_map.length != maxalpha+1)) {
                    alpha_map = new byte[maxalpha+1];
                    for (int i = 0; i <= maxalpha; i++) {
                        alpha_map[i] = (byte) ((i*255 + maxalpha/2)/maxalpha);
                    }
                }
            }

            @Override
            public boolean supportBlockFlags() {
                return false;
            }

            @Override
            public void clearAlphas(final int pix_y) {
                // noop
            }

            @Override
            public void setAndClearRelativeAlphas(final int[] alphaDeltas, final int pix_y,
                                                  final int pix_from, final int pix_to)
            {
                // use x instead of pix_from as it cause artefacts:
                // note: it would be more efficient to skip all those empty pixels [x to pix_from[
                // but the native implementation must be fixed too.
//                pr.emitAndClearAlphaRow(alpha_map, alphaDeltas, pix_y, pix_from, pix_to, rowNum);
                pr.emitAndClearAlphaRow(alpha_map, alphaDeltas, pix_y, x, pix_to, rowNum);
                rowNum++;

                // clear properly the end of the alphaDeltas:
                final int to = pix_to - x;
                if (w < to) {
                    alphaDeltas[w] = 0;
                }
                alphaDeltas[to] = 0;

                if (MarlinConst.DO_CHECKS) {
                    IntArrayCache.check(alphaDeltas, pix_from - x, pix_to - x + 1, 0);
                }
            }

            @Override
            public void setAndClearRelativeAlphas(final int[] blkFlags, final int[] alphaDeltas, final int pix_y,
                                                  final int pix_from, final int pix_to)
            {
                throw new UnsupportedOperationException();
            }
        }
    }

    SWContext(ResourceFactory factory) {
        this.factory = factory;
        if (PrismSettings.useMarlinRasterizer) {
            this.shapeRenderer = new MarlinShapeRenderer();
        } else if (PrismSettings.doNativePisces) {
            this.shapeRenderer = new NativeShapeRenderer();
        } else {
            this.shapeRenderer = new JavaShapeRenderer();
        }
    }

    void renderShape(PiscesRenderer pr, Shape shape, BasicStroke stroke, BaseTransform tr, Rectangle clip, boolean antialiasedShape) {
        this.shapeRenderer.renderShape(pr, shape, stroke, tr, clip, antialiasedShape);
    }

    private SWRTTexture initRBBuffer(int width, int height) {
        final SWRTTexture tex = (SWRTTexture)factory.createRTTexture(width, height, Texture.WrapMode.CLAMP_NOT_NEEDED);
        readBackBufferRef = new SoftReference<SWRTTexture>(tex);
        return tex;
    }

    private void disposeRBBuffer() {
        if (readBackBufferRef != null) {
            readBackBufferRef.clear();
            readBackBufferRef = null;
        }
    }

    SWRTTexture validateRBBuffer(int width, int height) {
        SWRTTexture tex;
        if (readBackBufferRef == null) {
            tex = this.initRBBuffer(width, height);
        } else {
            tex = readBackBufferRef.get();
            if (tex == null ||
                tex.getPhysicalWidth() < width ||
                tex.getPhysicalHeight() < height)
            {
                this.disposeRBBuffer();
                tex = this.initRBBuffer(width, height);
            }
            tex.setContentWidth(width);
            tex.setContentHeight(height);
        }
        return tex;
    }

    private SWArgbPreTexture initImagePaintTexture(int width, int height) {
        final SWArgbPreTexture tex = (SWArgbPreTexture)factory.createTexture(PixelFormat.INT_ARGB_PRE,
                Texture.Usage.DEFAULT, Texture.WrapMode.REPEAT, width, height);
        imagePaintTextureRef = new SoftReference<SWArgbPreTexture>(tex);
        return tex;
    }

    private void disposeImagePaintTexture() {
        if (imagePaintTextureRef != null) {
            imagePaintTextureRef.clear();
            imagePaintTextureRef = null;
        }
    }

    SWArgbPreTexture validateImagePaintTexture(int width, int height) {
        SWArgbPreTexture tex;
        if (imagePaintTextureRef == null) {
            tex = this.initImagePaintTexture(width, height);
        } else {
            tex = imagePaintTextureRef.get();
            if (tex == null ||
                tex.getPhysicalWidth() < width ||
                tex.getPhysicalHeight() < height)
            {
                this.disposeImagePaintTexture();
                tex = this.initImagePaintTexture(width, height);
            }
            tex.setContentWidth(width);
            tex.setContentHeight(height);
        }
        return tex;
    }


    void dispose() {
        this.disposeRBBuffer();
        this.disposeImagePaintTexture();
        this.shapeRenderer.dispose();
    }
}
