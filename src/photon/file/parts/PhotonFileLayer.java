/*
 * MIT License
 *
 * Copyright (c) 2018 Bonosoft
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package photon.file.parts;

import photon.file.PhotonFile;

import java.io.ByteArrayInputStream;
import java.util.*;

/**
 * by bn on 01/07/2018.
 */
public class PhotonFileLayer {
    private float layerPositionZ;
    private float layerExposure;
    private float layerOffTimeSeconds;
    private int dataAddress;
    private int dataSize;
    private int unknown1;
    private int unknown2;
    private int unknown3;
    private int unknown4;

    private byte[] imageData;

    private byte[] packedLayerImage;

    private ArrayList<BitSet> islandRows;
    private int isLandsCount;

    private ArrayList<BitSet> islandSupportedRows;
    private int islandSupportedCount;
    private long pixels;

    private ArrayList<PhotonFileLayer> antiAliasLayers = new ArrayList<>();

    private boolean extendsMargin;
    private PhotonFileHeader photonFileHeader;
    public boolean isCalculated;

    private PhotonFileLayer(PhotonInputStream ds) throws Exception {
        layerPositionZ = ds.readFloat();
        layerExposure = ds.readFloat();
        layerOffTimeSeconds = ds.readFloat();

        dataAddress = ds.readInt();
        dataSize = ds.readInt();

        unknown1 = ds.readInt();
        unknown2 = ds.readInt();
        unknown3 = ds.readInt();
        unknown4 = ds.readInt();
    }

    public PhotonFileLayer(PhotonFileLayer photonFileLayer, PhotonFileHeader photonFileHeader) {
        layerPositionZ = photonFileLayer.layerPositionZ;
        layerExposure = photonFileLayer.layerExposure;
        layerOffTimeSeconds = photonFileLayer.layerOffTimeSeconds;
        dataAddress = photonFileLayer.dataAddress;
        dataAddress = photonFileLayer.dataSize;

        this.photonFileHeader = photonFileHeader;

        // Dont copy data, we are building new AA layers anyway
        //this.imageData = copy();
        //this.packedLayerImage = copy();
    }

    public int savePos(int dataPosition) throws Exception {
        dataAddress = dataPosition;
        return dataPosition + dataSize;
    }

    public void save(PhotonOutputStream os) throws Exception {
        os.writeFloat(layerPositionZ);
        os.writeFloat(layerExposure);
        os.writeFloat(layerOffTimeSeconds);

        os.writeInt(dataAddress);
        os.writeInt(dataSize);

        os.writeInt(unknown1);
        os.writeInt(unknown2);
        os.writeInt(unknown3);
        os.writeInt(unknown4);
    }

    public void saveData(PhotonOutputStream os) throws Exception {
        os.write(imageData, 0, dataSize);
    }

    public static int getByteSize() {
        return 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4;
    }

    public ArrayList<BitSet> unpackImage(int resolutionX) {
        pixels = 0;
        resolutionX = resolutionX - 1;
        ArrayList<BitSet> unpackedImage = new ArrayList<>();
        BitSet currentRow = new BitSet();
        unpackedImage.add(currentRow);
        int x = 0;
        for (byte rle : imageData) {
            int length = rle & 0x7F;
            boolean color = (rle & 0x80) == 0x80;
            if (color) {
                pixels += length;
            }
            int endPosition = x + (length - 1);
            int lineEnd = Integer.min(endPosition, resolutionX);
            if (color) {
                currentRow.set(x, 1 + lineEnd);
            }
            if (endPosition > resolutionX) {
                currentRow = new BitSet();
                unpackedImage.add(currentRow);
                lineEnd = endPosition - (resolutionX + 1);
                if (color) {
                    currentRow.set(0, 1 + lineEnd);
                }
            }
            x = lineEnd + 1;
            if (x > resolutionX) {
                currentRow = new BitSet();
                unpackedImage.add(currentRow);
                x = 0;
            }
        }
        return unpackedImage;
    }

    private void aaPixels(ArrayList<BitSet> unpackedImage, PhotonLayer photonLayer) {
        photonLayer.clear();

        for (int y = 0; y < unpackedImage.size(); y++) {
            BitSet currentRow = unpackedImage.get(y);
            if (currentRow != null) {
                for (int x = 0; x < currentRow.length(); x++) {
                    if (currentRow.get(x)) {
                        photonLayer.unSupported(x, y);
                    }
                }
            }
        }
    }

    private void unknownPixels(ArrayList<BitSet> unpackedImage, PhotonLayer photonLayer) {
        photonLayer.clear();

        for (int y = 0; y < unpackedImage.size(); y++) {
            BitSet currentRow = unpackedImage.get(y);
            if (currentRow != null) {
                for (int x = 0; x < currentRow.length(); x++) {
                    if (currentRow.get(x)) {
                        photonLayer.supported(x, y);
                    }
                }
            }
        }
    }

    /**
     * Calculates the type of pixels for the layer taking into account the previous one
     *
     * @param unpackedImage
     * @param previousUnpackedImage
     * @param photonLayer
     * @param previousLayer
     * @return true if anything has changed
     */
    private boolean calculate(ArrayList<BitSet> unpackedImage, ArrayList<BitSet> previousUnpackedImage,
                              PhotonLayer photonLayer, PhotonLayer previousLayer, PhotonLayer oldLayer) {
        boolean hasAnythingChanged = false;
        islandRows = new ArrayList<>();
        islandSupportedRows = new ArrayList<>();
        isLandsCount = 0;

        photonLayer.clear();

        for (int y = 0; y < unpackedImage.size(); y++) {
            BitSet currentRow = unpackedImage.get(y);
            BitSet prevRow = previousUnpackedImage != null ? previousUnpackedImage.get(y) : null;
            if (currentRow != null) {
                for (int x = 0; x < currentRow.length(); x++) {
                    if (currentRow.get(x)) {
                        if (prevRow == null || prevRow.get(x)) {
                            if (previousLayer != null && (previousLayer.get(x, y) == PhotonLayer.ISLAND
                                    || (previousLayer.get(x, y) == PhotonLayer.ISLAND_SUPPORT))) {
                                photonLayer.supportedByIsland(x, y);
                            } else {
                                photonLayer.supported(x, y);
                            }
                        } else {
                            photonLayer.island(x, y);
                        }
                    }
                }
            }
        }

        photonLayer.reduce();

        isLandsCount = photonLayer.setIslands(islandRows);
        islandSupportedCount = photonLayer.setIslandSupported(islandSupportedRows);
        if (oldLayer != null) {
            hasAnythingChanged = !Arrays.deepEquals(photonLayer.getiArray(), oldLayer.getiArray());
        }
        return hasAnythingChanged;
    }


    public static List<PhotonFileLayer> readLayers(PhotonFileHeader photonFileHeader, byte[] file, int margin, IPhotonProgress iPhotonProgress) throws Exception {
        PhotonLayer photonLayer = new PhotonLayer(photonFileHeader.getResolutionX(), photonFileHeader.getResolutionY());

        List<PhotonFileLayer> layers = new ArrayList<>();

        int antiAliasLevel = 1;
        if (photonFileHeader.getVersion() > 1) {
            antiAliasLevel = photonFileHeader.getAntiAliasingLevel();
        }

        int layerCount = photonFileHeader.getNumberOfLayers();

        try (PhotonInputStream ds = new PhotonInputStream(new ByteArrayInputStream(file, photonFileHeader.getLayersDefinitionOffsetAddress(), file.length))) {
            Hashtable<Integer, PhotonFileLayer> layerMap = new Hashtable<>();
            for (int i = 0; i < layerCount; i++) {

                iPhotonProgress.showInfo("Reading photon file layer " + (i + 1) + "/" + photonFileHeader.getNumberOfLayers());

                PhotonFileLayer layer = new PhotonFileLayer(ds);
                layer.photonFileHeader = photonFileHeader;
                layer.imageData = Arrays.copyOfRange(file, layer.dataAddress, layer.dataAddress + layer.dataSize);
                layers.add(layer);
                layerMap.put(i, layer);
            }

            if (antiAliasLevel > 1) {
                for (int a = 0; a < (antiAliasLevel - 1); a++) {
                    for (int i = 0; i < layerCount; i++) {
                        iPhotonProgress.showInfo("Reading photon file AA " + (2 + a) + "/" + antiAliasLevel + " layer " + (i + 1) + "/" + photonFileHeader.getNumberOfLayers());

                        PhotonFileLayer layer = new PhotonFileLayer(ds);
                        layer.photonFileHeader = photonFileHeader;
                        layer.imageData = Arrays.copyOfRange(file, layer.dataAddress, layer.dataAddress + layer.dataSize);

                        layerMap.get(i).addAntiAliasLayer(layer);

                    }
                }
            }
        }

        photonLayer.unLink();

        return layers;
    }

    private void addAntiAliasLayer(PhotonFileLayer layer) {
        antiAliasLayers.add(layer);
    }

    public static void calculateAALayers(PhotonFileHeader photonFileHeader, List<PhotonFileLayer> layers, PhotonAaMatrix photonAaMatrix, IPhotonProgress iPhotonProgress) throws Exception {
        PhotonLayer photonLayer = new PhotonLayer(photonFileHeader.getResolutionX(), photonFileHeader.getResolutionY());
        int[][] source = new int[photonFileHeader.getResolutionY()][photonFileHeader.getResolutionX()];

        int i = 0;
        for (PhotonFileLayer layer : layers) {
            ArrayList<BitSet> unpackedImage = layer.unpackImage(photonFileHeader.getResolutionX());

            iPhotonProgress.showInfo("Calculating AA for photon file layer " + i + "/" + photonFileHeader.getNumberOfLayers());


            for (int y = 0; y < photonFileHeader.getResolutionY(); y++) {
                for (int x = 0; x < photonFileHeader.getResolutionX(); x++) {
                    source[y][x] = 0;
                }
            }

            for (int y = 0; y < unpackedImage.size(); y++) {
                BitSet currentRow = unpackedImage.get(y);
                if (currentRow != null) {
                    for (int x = 0; x < currentRow.length(); x++) {
                        if (currentRow.get(x)) {
                            source[y][x] = 255;
                        }
                    }
                }
            }

            // Calc
            int[][] target = photonAaMatrix.calc(source);

            int aaTresholdDiff = 255 / photonFileHeader.getAntiAliasingLevel();
            int aaTreshold = 0;
            for (PhotonFileLayer aaFileLayer : layer.antiAliasLayers) {
                photonLayer.clear();
                aaTreshold += aaTresholdDiff;

                for (int y = 0; y < photonFileHeader.getResolutionY(); y++) {
                    for (int x = 0; x < photonFileHeader.getResolutionX(); x++) {
                        if (target[y][x] >= aaTreshold) {
                            photonLayer.supported(x, y);
                        }
                    }
                }

                aaFileLayer.saveLayer(photonLayer);
            }

            i++;
        }
        photonLayer.unLink();

    }

    public static void calculateLayers(PhotonFile photonFile, IPhotonProgress iPhotonProgress) throws Exception {
        PhotonFileHeader photonFileHeader = photonFile.getPhotonFileHeader();
        List<PhotonFileLayer> layers = photonFile.getLayers();
        int margin = photonFile.getMargin();
        ArrayList<BitSet> previousUnpackedImage = null;
        PhotonLayer photonLayer = new PhotonLayer(photonFileHeader.getResolutionX(), photonFileHeader.getResolutionY());
        PhotonLayer previousLayer = new PhotonLayer(photonFileHeader.getResolutionX(), photonFileHeader.getResolutionY());
        SortedSet<PhotonMultiLayerIsland> prevLayerIslands = new TreeSet<>();
        PhotonLayer tmp;

        photonFile.restartIslandInfo();
        int i = 0;
        for (PhotonFileLayer layer : layers) {
            ArrayList<BitSet> unpackedImage = layer.unpackImage(photonFileHeader.getResolutionX());

            iPhotonProgress.showInfo("Calculating photon file layer " + i + "/" + photonFileHeader.getNumberOfLayers());

            if (margin > 0) {
                layer.extendsMargin = layer.checkMagin(unpackedImage, margin);
            }

            layer.unknownPixels(unpackedImage, photonLayer);

            layer.calculate(unpackedImage, previousUnpackedImage, photonLayer, previousLayer, null);

            if (previousUnpackedImage != null) {
                previousUnpackedImage.clear();
            }
            previousUnpackedImage = unpackedImage;

            layer.packedLayerImage = photonLayer.packLayerImage();
            layer.isCalculated = true;

            if (photonFileHeader.getVersion() > 1) {
                for (PhotonFileLayer aaFileLayer : layer.antiAliasLayers) {
                    ArrayList<BitSet> aaUnpackedImage = aaFileLayer.unpackImage(photonFileHeader.getResolutionX());
                    PhotonLayer aaPhotonLayer = new PhotonLayer(photonFileHeader.getResolutionX(), photonFileHeader.getResolutionY());
                    aaFileLayer.unknownPixels(aaUnpackedImage, aaPhotonLayer);
                    aaFileLayer.packedLayerImage = aaPhotonLayer.packLayerImage();
                    aaFileLayer.isCalculated = false;
                }
            }
            photonFile.findIslands(i, layer);
            tmp = previousLayer;
            previousLayer = photonLayer;
            photonLayer = tmp;
            i++;
        }
        photonLayer.unLink();
        previousLayer.unLink();
        photonFile.reduceMultiLayerIslands();
    }

    public static void calculateLayers(PhotonFile photonFile, int layerNo, int iterations) throws Exception {
        PhotonFileHeader photonFileHeader = photonFile.getPhotonFileHeader();
        List<PhotonFileLayer> layers = photonFile.getLayers();
        int margin = photonFile.getMargin();
        PhotonLayer photonLayer = new PhotonLayer(photonFileHeader.getResolutionX(), photonFileHeader.getResolutionY());
        PhotonLayer oldLayer = new PhotonLayer(photonFileHeader.getResolutionX(), photonFileHeader.getResolutionY());
        PhotonLayer previousLayer = new PhotonLayer(photonFileHeader.getResolutionX(), photonFileHeader.getResolutionY());
        SortedSet<PhotonMultiLayerIsland> prevLayerIslands = photonFile.getMultiLayerIslands();
        PhotonLayer tmp;
        ArrayList<BitSet> previousUnpackedImage = null;

//        photonFile.restartIslandInfo();
        if (layerNo > 0) {
            PhotonFileLayer previousFileLayer = layers.get(layerNo - 1);
            previousUnpackedImage = previousFileLayer.unpackImage(photonFileHeader.getResolutionX());
            previousLayer.unpackLayerImage(previousFileLayer.packedLayerImage);
        }
        boolean layerChanged = false;
        for (int i = 0; (iterations == -1 || i < iterations) && (i < 2 || layerChanged); i++) {
            PhotonFileLayer layer = layers.get(layerNo + i);
            ArrayList<BitSet> unpackedImage = layer.unpackImage(photonFileHeader.getResolutionX());

            if (margin > 0) {
                layer.extendsMargin = layer.checkMagin(unpackedImage, margin);
            }

            layer.unknownPixels(unpackedImage, photonLayer);

            oldLayer.unpackLayerImage(layer.packedLayerImage);
            layerChanged = layer.calculate(unpackedImage, previousUnpackedImage, photonLayer, previousLayer, oldLayer);

            if (previousUnpackedImage != null) {
                previousUnpackedImage.clear();
            }
            previousUnpackedImage = unpackedImage;

            layer.packedLayerImage = photonLayer.packLayerImage();
            layer.isCalculated = true;

            photonFile.findIslands(layerNo + i, layer);
            tmp = previousLayer;
            previousLayer = photonLayer;
            photonLayer = tmp;
        }
        photonLayer.unLink();
        previousLayer.unLink();
        photonFile.reduceMultiLayerIslands();
    }

    public ArrayList<PhotonRow> getRows() {
        return PhotonLayer.getRows(packedLayerImage, photonFileHeader.getResolutionX(), isCalculated);
    }

    public ArrayList<BitSet> getIslandRows() {
        return islandRows;
    }

    public int getIsLandsCount() {
        return isLandsCount;
    }
    public int getIslandSupportedCount() {
        return islandSupportedCount;
    }

    public long getPixels() {
        return pixels;
    }

    public float getLayerPositionZ() {
        return layerPositionZ;
    }

    public void setLayerPositionZ(float layerPositionZ) {
        this.layerPositionZ = layerPositionZ;
    }

    public float getLayerExposure() {
        return layerExposure;
    }

    public float getLayerOffTime() {
        return layerOffTimeSeconds;
    }

    public void setLayerExposure(float layerExposure) {
        this.layerExposure = layerExposure;
    }

    public void setLayerOffTimeSeconds(float layerOffTimeSeconds) {
        this.layerOffTimeSeconds = layerOffTimeSeconds;
    }

    public void unLink() {
        imageData = null;
        packedLayerImage = null;
        if (islandRows != null) {
            islandRows.clear();
        }
        if (islandSupportedRows != null) {
            islandSupportedRows.clear();
        }
        photonFileHeader = null;
    }

    public boolean doExtendMargin() {
        return extendsMargin;
    }

    private boolean checkMagin(ArrayList<BitSet> unpackedImage, int margin) {
        if (unpackedImage.size() > margin) {
            // check top margin rows
            for (int i = 0; i < margin; i++) {
                if (!unpackedImage.get(i).isEmpty()) {
                    return true;
                }
            }
            // check bottom margin rows
            for (int i = unpackedImage.size() - margin; i < unpackedImage.size(); i++) {
                if (!unpackedImage.get(i).isEmpty()) {
                    return true;
                }
            }

            for (int i = margin; i < unpackedImage.size() - margin; i++) {
                BitSet row = unpackedImage.get(i);
                int nextBit = row.nextSetBit(0);
                if (nextBit >= 0 && nextBit < margin) {
                    return true;
                }
                nextBit = row.nextSetBit(photonFileHeader.getResolutionX() - margin);
                if (nextBit > photonFileHeader.getResolutionX() - margin) {
                    return true;
                }
            }

        }
        return false;
    }

    public PhotonLayer getLayer() {
        PhotonLayer photonLayer = new PhotonLayer(photonFileHeader.getResolutionX(), photonFileHeader.getResolutionY());
        photonLayer.unpackLayerImage(packedLayerImage);
        return photonLayer;
    }

    public byte[] getPackedLayerImage() {
        return packedLayerImage;
    }

    public void getUpdateLayer(PhotonLayer photonLayer) {
        photonLayer.unpackLayerImage(packedLayerImage);
    }

    public void updateLayerIslands(PhotonLayer photonLayer) {
        islandRows = new ArrayList<>();
        islandSupportedRows = new ArrayList<>();
        isLandsCount = photonLayer.setIslands(islandRows);
        islandSupportedCount = photonLayer.setIslandSupported(islandSupportedRows);
    }

    public void saveLayer(PhotonLayer photonLayer) throws Exception {
        this.packedLayerImage = photonLayer.packLayerImage();
        this.imageData = photonLayer.packImageData();
        this.dataSize = imageData.length;
        islandRows = new ArrayList<>();
        islandSupportedRows = new ArrayList<>();
        isLandsCount = photonLayer.setIslands(islandRows);
        islandSupportedCount = photonLayer.setIslandSupported(islandSupportedRows);
    }

    public ArrayList<BitSet> getUnknownRows() {
        return unpackImage(photonFileHeader.getResolutionX());
    }

    public PhotonFileLayer getAntiAlias(int a) {
        if (antiAliasLayers.size() > a) {
            return antiAliasLayers.get(a);
        }
        return null;
    }

    public ArrayList<PhotonFileLayer> getAntiAlias() {
        return antiAliasLayers;
    }

    public SortedSet<PhotonRect> getIslandsRects() {
        SortedSet<PhotonRect> islandsRects = new TreeSet<>();
        PhotonLayer layer = new PhotonLayer(photonFileHeader.getResolutionX(), photonFileHeader.getResolutionY());
        layer.unpackLayerImage(this.packedLayerImage);
        int[] rowIslands = layer.getRowIslands();
        int[] rowIslandSupported = layer.getRowIslandSupported();
        for (int x = 0; x < Math.max(rowIslands.length, rowIslandSupported.length); x++) {
            if (x < rowIslands.length && rowIslands[x] > 0) {
                addCollidingRects(islandRows.get(x), x, islandsRects);
            }
            if (x < rowIslandSupported.length && rowIslandSupported[x] > 0) {
                addCollidingRects(islandSupportedRows.get(x), x, islandsRects);
            }
        }
        return islandsRects;
    }

    private void addCollidingRects(BitSet cols, int x, Set<PhotonRect> islandsRects) {
        List<PhotonRect> rowRects = findRects(cols, x);
        for (PhotonRect rowRect : rowRects) {
            boolean merge = false;
            for (PhotonRect prevLayerRect : islandsRects) {
                if (prevLayerRect.inContactWith(rowRect)) {
                    prevLayerRect.merge(rowRect);
                    merge = true;
                }
            }
            if (!merge) {
                islandsRects.add(rowRect);
            }
        }
    }

    private List<PhotonRect> findRects(BitSet cols, int x) {
        List<PhotonRect> rects = new ArrayList<>();
        int pos = 0;
        while (pos < cols.size()) {
            int start = cols.nextSetBit(pos);
            if (start < cols.size() && start > -1) {
                int end = cols.nextClearBit(start);
                pos = end;
                if (end == -1) {
                    end = cols.size() - 1;
                }
                rects.add(new PhotonRect(x, start, x, end));
            } else {
                break;
            }
        }
        return rects;
    }

    public void removeIslands(Set<PhotonMultiLayerIsland> islandsToRemove) throws Exception {
        PhotonLayer photonLayer = new PhotonLayer(photonFileHeader.getResolutionX(), photonFileHeader.getResolutionY());
        getUpdateLayer(photonLayer);
        for (PhotonMultiLayerIsland island : islandsToRemove) {
            removeIslands(photonLayer, island.getRect());
        }
    }

    private void removeIslands(PhotonLayer photonLayer, PhotonRect rect) throws Exception {
        for (int x = rect.getX1(); x <= rect.getX2(); x++) {
            if (photonLayer.getRowIslands()[x] > 0 || photonLayer.getRowIslandSupported()[x] > 0) {
                for (int y = rect.getY1(); y <= rect.getY2(); y++) {
                    if (photonLayer.getiArray()[x][y] == PhotonLayer.ISLAND
                            || photonLayer.getiArray()[x][y] == PhotonLayer.ISLAND_SUPPORT)
                        photonLayer.transformTo(y, x, PhotonLayer.OFF);
                }
            }
        }
        this.saveLayer(photonLayer);
    }

}
