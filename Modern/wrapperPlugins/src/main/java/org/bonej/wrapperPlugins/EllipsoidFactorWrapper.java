package org.bonej.wrapperPlugins;

import net.imagej.ImgPlus;
import net.imagej.display.ColorTables;
import net.imagej.ops.OpService;
import net.imagej.units.UnitService;
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.algorithm.binary.Thresholder;
import net.imglib2.algorithm.neighborhood.HyperSphereShape;
import net.imglib2.algorithm.neighborhood.Shape;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.NativeType;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.ValuePair;
import org.apache.commons.math3.util.CombinatoricsUtils;
import org.bonej.ops.ellipsoid.Ellipsoid;
import org.bonej.ops.ellipsoid.FindLocalEllipsoidOp;
import org.bonej.utilities.AxisUtils;
import org.bonej.utilities.ElementUtil;
import org.bonej.wrapperPlugins.wrapperUtils.Common;
import org.scijava.ItemIO;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.ui.DialogPrompt;
import org.scijava.ui.UIService;
import org.scijava.vecmath.Matrix3d;
import org.scijava.vecmath.Vector3d;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static net.imglib2.roi.Regions.countTrue;
import static org.bonej.utilities.AxisUtils.getSpatialUnit;
import static org.bonej.utilities.Streamers.spatialAxisStream;
import static org.bonej.wrapperPlugins.CommonMessages.*;
import static org.scijava.ui.DialogPrompt.MessageType.WARNING_MESSAGE;
import static org.scijava.ui.DialogPrompt.OptionType.OK_CANCEL_OPTION;
import static org.scijava.ui.DialogPrompt.Result.OK_OPTION;

/**
 * Ellipsoid Factor
 * <p>
 * Ellipsoid
 * </p>
 *
 * @author Alessandro Felder
 */

@Plugin(type = Command.class, menuPath = "Plugins>BoneJ>Ellipsoid Factor 2")
public class EllipsoidFactorWrapper<R extends RealType<R> & NativeType<R>> extends ContextCommand {

    private final FindLocalEllipsoidOp findLocalEllipsoidOp = new FindLocalEllipsoidOp();

    @SuppressWarnings("unused")
    @Parameter(validater = "validateImage")
    private ImgPlus<R> inputImage;

    @Parameter(persist = false, required = false)
    private DoubleType sigma = new DoubleType(0);

    @Parameter(persist = false, required = false)
    private DoubleType percentageOfRidgePoints = new DoubleType(0.95);

    @Parameter(label = "Ridge image", type = ItemIO.OUTPUT)
    private ImgPlus<UnsignedByteType> ridgePointsImage;

    @Parameter(label = "EF image", type = ItemIO.OUTPUT)
    private ImgPlus<FloatType> efImage;

    @Parameter(label = "ID image", type = ItemIO.OUTPUT)
    private ImgPlus<IntType> eIdImage;

    @Parameter(label = "Volume Image", type = ItemIO.OUTPUT)
    private ImgPlus<FloatType> vImage;

    @Parameter(label = "a/b Image", type = ItemIO.OUTPUT)
    private ImgPlus<FloatType> aToBAxisRatioImage;

    @Parameter(label = "b/c Image", type = ItemIO.OUTPUT)
    private ImgPlus<FloatType> bToCAxisRatioImage;

    @Parameter(label = "Unweighted Flinn Plot", type = ItemIO.OUTPUT)
    private ImgPlus<BitType> flinnPlotImage;

    @Parameter(label = "Flinn Peak Plot", type = ItemIO.OUTPUT)
    private ImgPlus<FloatType> flinnPeakPlotImage;

    @SuppressWarnings("unused")
    @Parameter
    private OpService opService;

    @SuppressWarnings("unused")
    @Parameter
    private StatusService statusService;

    @SuppressWarnings("unused")
    @Parameter
    private UIService uiService;

    @SuppressWarnings("unused")
    @Parameter
    private PrefService prefService;

    @SuppressWarnings("unused")
    @Parameter
    private UnitService unitService;

    private boolean calibrationWarned;

    @Override
    public void run() {

        statusService.showStatus("Ellipsoid Factor: initialising...");
        Img<BitType> inputAsBit = Common.toBitTypeImgPlus(opService,inputImage);
        final RandomAccess<BitType> inputBitRA = inputAsBit.randomAccess();

        //find clever seed and boundary points
        final Img<R> distanceTransform = (Img<R>) opService.image().distancetransform(inputAsBit);
        int nSphere = 12;//estimateNSpiralPointsRequired(estimatedCharacteristicLength.get(), samplingWidth);
        final List<Vector3d> sphereSamplingDirections = getGeneralizedSpiralSetOnSphere(nSphere);
        sphereSamplingDirections.addAll(Arrays.asList(
                new Vector3d(1,0,0),new Vector3d(0,1,0),new Vector3d(0,0,1),
                new Vector3d(-1,0,0),new Vector3d(0,-1,0),new Vector3d(0,0,-1)));
        final List<Vector3d> filterSamplingDirections = getGeneralizedSpiralSetOnSphere(12);

        List<Shape> shapes = new ArrayList<>();
        shapes.add(new HyperSphereShape(2));
        final IterableInterval<R> open = opService.morphology().open(distanceTransform, shapes);
        final IterableInterval<R> close = opService.morphology().close(distanceTransform, shapes);
        final IterableInterval<R> ridge = opService.math().subtract(close, open);
        final Cursor<R> ridgeCursor = ridge.localizingCursor();

        //remove ridgepoints in BG - how does this make a difference?
        while(ridgeCursor.hasNext()){
            ridgeCursor.fwd();
            long[] position = new long[3];
            ridgeCursor.localize(position);
            inputBitRA.setPosition(position);
            if(!inputBitRA.get().get())
            {
                ridgeCursor.get().setReal(0.0f);
            }
        }

        final double ridgePointCutOff = percentageOfRidgePoints.getRealFloat()*opService.stats().max(ridge).getRealFloat();
        final Img<R> ridgeImg = (Img) ridge;
        final Img<BitType> thresholdedRidge = Thresholder.threshold(ridgeImg, (R) new FloatType((float) ridgePointCutOff), true, 1);
        ridgePointsImage = new ImgPlus<>(opService.convert().uint8(thresholdedRidge), "Seeding Points");

        final List<ValuePair<List<Vector3d>, List<ValuePair<Vector3d, Vector3d>>>> starVolumes = new ArrayList<>();
        final List<Vector3d> internalSeedPoints = new ArrayList<>();
        List<ValuePair<List<ValuePair<Vector3d, Vector3d>>,Vector3d>> combos = new ArrayList<>();

        ridgeCursor.reset();
        while (ridgeCursor.hasNext()) {
            ridgeCursor.fwd();
            final double localValue = ridgeCursor.get().getRealFloat();
            if (localValue > ridgePointCutOff) {
                final List<ValuePair<Vector3d, Vector3d>> seedPoints = new ArrayList<>();
                long[] position = new long[3];
                ridgeCursor.localize(position);
                final Vector3d internalSeedPoint = new Vector3d(position[0]+0.5, position[1]+0.5, position[2]+0.5);
                internalSeedPoints.add(internalSeedPoint);
                List<Vector3d> contactPoints = sphereSamplingDirections.stream().map(d -> {
                    final Vector3d direction = new Vector3d(d);
                    return findFirstPointInBGAlongRay(direction, internalSeedPoint);
                }).collect(toList());

                contactPoints.forEach(c -> {
                    Vector3d inwardDirection = new Vector3d(internalSeedPoint);
                    inwardDirection.sub(c);
                    inwardDirection.normalize();
                    seedPoints.add(new ValuePair<>(c, inwardDirection));
                });
                combos.addAll(getAllCombosOfFour(seedPoints, internalSeedPoint));
            }
        }

        statusService.showStatus("Ellipsoid Factor: finding ellipsoids...");

        final List<Ellipsoid> ellipsoids = combos.parallelStream().map(c -> findLocalEllipsoidOp.calculate(c.getA(),c.getB())).filter(Optional::isPresent).map(Optional::get).filter(e -> whollyContainedInForeground(e, filterSamplingDirections)).collect(Collectors.toList());
        ellipsoids.sort(Comparator.comparingDouble(e -> -e.getVolume()));

        //find EF values
        statusService.showStatus("Ellipsoid Factor: preparing assignment...");
        final Img<IntType> ellipsoidIdentityImage = ArrayImgs.ints(inputImage.dimension(0), inputImage.dimension(1), inputImage.dimension(2));
        ellipsoidIdentityImage.cursor().forEachRemaining(c -> c.setInteger(-1));
        final Img<FloatType> ellipsoidFactorImage = ArrayImgs.floats(inputImage.dimension(0), inputImage.dimension(1), inputImage.dimension(2));
        ellipsoidFactorImage.cursor().forEachRemaining(c -> c.setReal(Double.NaN));

        final Img<FloatType> volumeImage = ArrayImgs.floats(inputImage.dimension(0), inputImage.dimension(1), inputImage.dimension(2));
        volumeImage.cursor().forEachRemaining(c -> c.setReal(Double.NaN));
        final Img<FloatType> aToBImage = ArrayImgs.floats(inputImage.dimension(0), inputImage.dimension(1), inputImage.dimension(2));
        aToBImage.cursor().forEachRemaining(c -> c.setReal(Double.NaN));
        final Img<FloatType> bToCImage = ArrayImgs.floats(inputImage.dimension(0), inputImage.dimension(1), inputImage.dimension(2));
        bToCImage.cursor().forEachRemaining(c -> c.setReal(Double.NaN));

        final Cursor<BitType> inputCursor = inputAsBit.localizingCursor();
        long numberOfForegroundVoxels = countTrue(inputAsBit);
        List<Vector3d> voxelCentrePoints = new ArrayList<>();

        while (inputCursor.hasNext()) {
            inputCursor.fwd();
            if(inputCursor.get().get()) {
                long [] coordinates = new long[3];
                inputCursor.localize(coordinates);
                voxelCentrePoints.add(new Vector3d(coordinates[0]+0.5, coordinates[1]+0.5, coordinates[2]+0.5));
            }
        }

        statusService.showStatus("Ellipsoid Factor: assigning EF to foreground voxels...");
        long numberOfAssignedVoxels = voxelCentrePoints.parallelStream().filter(centrePoint -> findID(ellipsoids,ellipsoidIdentityImage,centrePoint)).count();

        final double[] ellipsoidFactorArray = ellipsoids.parallelStream().mapToDouble(e -> computeEllipsoidFactor(e)).toArray();
        mapValuesToImage(ellipsoidFactorArray, ellipsoidIdentityImage, ellipsoidFactorImage);

        final double[] volumeArray = ellipsoids.parallelStream().mapToDouble(e -> e.getVolume()).toArray();
        mapValuesToImage(volumeArray, ellipsoidIdentityImage, volumeImage);

        final double[] aToBArray = ellipsoids.parallelStream().mapToDouble(e -> e.getA()/e.getB()).toArray();
        mapValuesToImage(aToBArray, ellipsoidIdentityImage, aToBImage);

        final double[] bToCArray = ellipsoids.parallelStream().mapToDouble(e -> e.getB()/e.getC()).toArray();
        mapValuesToImage(bToCArray, ellipsoidIdentityImage, bToCImage);

        long FlinnPlotDimension = 101; //several ellipsoids may fall in same bin if this is too small a number! This will be ignored!
        final Img<BitType> flinnPlot = ArrayImgs.bits(FlinnPlotDimension,FlinnPlotDimension);
        flinnPlot.cursor().forEachRemaining(c -> c.setZero());

        final RandomAccess<BitType> flinnRA = flinnPlot.randomAccess();
        for(int i=0; i<aToBArray.length; i++)
        {
            long x = Math.round(aToBArray[i]*(FlinnPlotDimension-1));
            long y = Math.round(bToCArray[i]*(FlinnPlotDimension-1));
            flinnRA.setPosition(new long[]{x,FlinnPlotDimension-y-1});
            flinnRA.get().setOne();
        }

        Img<FloatType> flinnPeakPlot = ArrayImgs.floats(FlinnPlotDimension,FlinnPlotDimension);
        flinnPeakPlot.cursor().forEachRemaining(c -> c.set(0.0f));

        final RandomAccess<FloatType> flinnPeakPlotRA = flinnPeakPlot.randomAccess();
        final RandomAccess<IntType> idAccess = ellipsoidIdentityImage.randomAccess();
        final Cursor<IntType> idCursor = ellipsoidIdentityImage.localizingCursor();
        while(idCursor.hasNext())
        {
            idCursor.fwd();
            if(idCursor.get().getInteger()>=0)
            {
                long[] position = new long[3];
                idCursor.localize(position);
                idAccess.setPosition(position);
                int localMaxEllipsoidID = idAccess.get().getInteger();
                long x = Math.round(aToBArray[localMaxEllipsoidID]*(FlinnPlotDimension-1));
                long y = Math.round(bToCArray[localMaxEllipsoidID]*(FlinnPlotDimension-1));
                flinnPeakPlotRA.setPosition(new long[]{x,FlinnPlotDimension-y-1});
                final float currentValue = flinnPeakPlotRA.get().getRealFloat();
                flinnPeakPlotRA.get().set(currentValue+1.0f);
            }
        }

        if(sigma.getRealDouble()>0.0)
        {
            flinnPeakPlot = (Img<FloatType>) opService.filter().gauss(flinnPeakPlot, sigma.get());
        }

        final LogService log = uiService.log();
        log.initialize();
        log.info("found "+ellipsoids.size()+" ellipsoids");
        log.info("assigned voxels = "+numberOfAssignedVoxels);
        log.info("foreground voxels = "+numberOfForegroundVoxels);
        double fillingPercentage = 100.0*((double) numberOfAssignedVoxels)/((double) numberOfForegroundVoxels);
        log.info("filling percentage = "+fillingPercentage+"%");

        efImage = new ImgPlus<>(ellipsoidFactorImage, "EF");
        efImage.setChannelMaximum(0,1);
        efImage.setChannelMinimum(  0,-1);
        efImage.initializeColorTables(1);
        efImage.setColorTable(ColorTables.FIRE, 0);

        eIdImage = new ImgPlus<>(ellipsoidIdentityImage, "ID");
        eIdImage.setChannelMaximum(0,ellipsoids.size()/10.0);
        eIdImage.setChannelMinimum(0, -1.0);

        vImage = new ImgPlus<>(volumeImage, "Volume");
        vImage.setChannelMaximum(0,ellipsoids.get(0).getVolume());
        vImage.setChannelMinimum(0, -1.0);

        aToBAxisRatioImage = new ImgPlus<>(aToBImage, "a/b");
        aToBAxisRatioImage.setChannelMaximum(0,1.0);
        aToBAxisRatioImage.setChannelMinimum(0, 0.0);

        bToCAxisRatioImage = new ImgPlus<>(bToCImage, "b/c");
        bToCAxisRatioImage.setChannelMaximum(0,1.0);
        bToCAxisRatioImage.setChannelMinimum(0, 0.0);

        flinnPlotImage = new ImgPlus<>(flinnPlot, "Unweighted Flinn Plot");
        flinnPlotImage.setChannelMaximum(0,255);
        flinnPlotImage.setChannelMinimum(0, 0);

        flinnPeakPlotImage = new ImgPlus<FloatType>(flinnPeakPlot, "Flinn Peak Plot");
        flinnPeakPlotImage.setChannelMaximum(0,255f);
        flinnPeakPlotImage.setChannelMinimum(0, 0.0f);
    }

    private void mapValuesToImage(double[] values, Img<IntType> ellipsoidIdentityImage, Img<FloatType> ellipsoidFactorImage) {
        final RandomAccess<FloatType> ef = ellipsoidFactorImage.randomAccess();
        final Cursor<IntType> id = ellipsoidIdentityImage.localizingCursor();
        while(id.hasNext()){
            id.fwd();
            if(id.get().getInteger()!=-1){
                long[] position = new long[3];
                id.localize(position);
                final double value = values[id.get().getInteger()];
                ef.setPosition(position);
                ef.get().setReal(value);
            }
        }
    }

    private boolean findID(List<Ellipsoid> ellipsoids, Img<IntType> ellipsoidIdentityImage, Vector3d point) {
        boolean assigned = false;

        //find largest ellipsoid containing current position
        int currentEllipsoidCounter = 0;
        while (currentEllipsoidCounter < ellipsoids.size() && !insideEllipsoid(point, ellipsoids.get(currentEllipsoidCounter))) {
            currentEllipsoidCounter++;
        }

        RandomAccess<IntType> eIDRandomAccess = ellipsoidIdentityImage.randomAccess();
        eIDRandomAccess.setPosition(vectorToPixelGrid(point));

        //ignore background voxels and voxels not contained in any ellipsoid
        if (currentEllipsoidCounter == ellipsoids.size()) {
             eIDRandomAccess.get().set(-1);
        } else {
            eIDRandomAccess.get().set(currentEllipsoidCounter);
            assigned = true;
        }
        return assigned;
    }

    private boolean whollyContainedInForeground(Ellipsoid e, List<Vector3d> sphereSamplingDirections) {
        if(!isInBounds(vectorToPixelGrid(e.getCentroid())))
        {
            return false;
        }

        List<Vector3d> axisSamplingDirections = new ArrayList<>();
        Matrix3d ellipsoidOrientation = new Matrix3d();
        e.getOrientation().getRotationScale(ellipsoidOrientation);
        axisSamplingDirections.add(new Vector3d(ellipsoidOrientation.getM00(),ellipsoidOrientation.getM01(),ellipsoidOrientation.getM02()));
        axisSamplingDirections.add(new Vector3d(-ellipsoidOrientation.getM00(),-ellipsoidOrientation.getM01(),-ellipsoidOrientation.getM02()));

        axisSamplingDirections.add(new Vector3d(ellipsoidOrientation.getM10(),ellipsoidOrientation.getM11(),ellipsoidOrientation.getM12()));
        axisSamplingDirections.add(new Vector3d(-ellipsoidOrientation.getM10(),-ellipsoidOrientation.getM11(),-ellipsoidOrientation.getM12()));

        axisSamplingDirections.add(new Vector3d(ellipsoidOrientation.getM20(),ellipsoidOrientation.getM21(),ellipsoidOrientation.getM22()));
        axisSamplingDirections.add(new Vector3d(-ellipsoidOrientation.getM20(),-ellipsoidOrientation.getM21(),-ellipsoidOrientation.getM22()));

        boolean ellipsoidsExtentsInForeground = !axisSamplingDirections.stream().anyMatch(dir -> ellipsoidIntersectionIsBackground(e,dir));
        return ellipsoidsExtentsInForeground && !sphereSamplingDirections.stream().anyMatch(dir -> ellipsoidIntersectionIsBackground(e,dir));
    }

    private boolean ellipsoidIntersectionIsBackground(Ellipsoid e, Vector3d dir) {
        double axisReduction = Math.sqrt(3);
        final Matrix3d Q = new Matrix3d();
        e.getOrientation().getRotationScale(Q);
        final Matrix3d lambda = new Matrix3d();
        double a = e.getA()-axisReduction;
        double b = e.getB()-axisReduction;
        double c = e.getC()-axisReduction;
        lambda.setM00(1.0/(a*a));
        lambda.setM11(1.0/(b*b));
        lambda.setM22(1.0/(c*c));
        lambda.mul(lambda, Q);
        lambda.mulTransposeLeft(Q, lambda);
        Vector3d ATimesDir = new Vector3d();
        lambda.transform(dir,ATimesDir);
        double surfaceIntersectionParameter = Math.sqrt(1.0/dir.dot(ATimesDir));
        Vector3d intersectionPoint = new Vector3d(dir);
        intersectionPoint.scaleAdd(surfaceIntersectionParameter,e.getCentroid());
        final long[] pixel = vectorToPixelGrid(intersectionPoint);
        if (isInBounds(pixel)) {
            final RandomAccess<R> inputRA = inputImage.getImg().randomAccess();
            inputRA.setPosition(pixel);
            return inputRA.get().getRealDouble() == 0;
        }
        else {
            return true;//false to have outside input image equals foreground
        }
    }

    private static float computeEllipsoidFactor(final Ellipsoid ellipsoid) {
        return (float) (ellipsoid.getA() / ellipsoid.getB() - ellipsoid.getB() / ellipsoid.getC());
    }

    static boolean insideEllipsoid(final Vector3d coordinates, final Ellipsoid ellipsoid) {
        Vector3d x = new Vector3d(coordinates);
        Vector3d centroid = ellipsoid.getCentroid();
        x.sub(centroid);

        if(x.length()>ellipsoid.getC()) return false;

        Matrix3d orientation = new Matrix3d();
        ellipsoid.getOrientation().getRotationScale(orientation);

        Matrix3d eigenMatrix = new Matrix3d();
        eigenMatrix.setM00(1.0 / (ellipsoid.getA() * ellipsoid.getA()));
        eigenMatrix.setM11(1.0 / (ellipsoid.getB() * ellipsoid.getB()));
        eigenMatrix.setM22(1.0 / (ellipsoid.getC() * ellipsoid.getC()));

        eigenMatrix.mul(eigenMatrix, orientation);
        eigenMatrix.mulTransposeLeft(orientation, eigenMatrix);

        Vector3d Ax = new Vector3d();
        eigenMatrix.transform(x, Ax);

        return x.dot(Ax) < 1;
    }

    /**
     * Method to numerically approximate equidistantly spaced points on the
     * surface of a sphere
     * <p>
     * The implementation follows the description of the theoretical work by
     * Rakhmanov et al., 1994 in Saff and Kuijlaars, 1997
     * (<a href="doi:10.1007/BF03024331">dx.doi.org/10.1007/BF03024331</a>), but k
     * is shifted by one to the left for more convenient indexing.
     *
     * @param n : number of points required (has to be > 2)
     * </p>
     */
    static List<Vector3d> getGeneralizedSpiralSetOnSphere(int n) {
        List<Vector3d> spiralSet = new ArrayList<>();

        List<Double> phi = new ArrayList<>();
        phi.add(0.0);
        for (int k = 1; k < n - 1; k++) {
            double h = -1.0 + 2.0 * ((double) k) / (n - 1);
            phi.add(getPhiByRecursion(n, phi.get(k - 1), h));
        }
        phi.add(0.0);

        for (int k = 0; k < n; k++) {
            double h = -1.0 + 2.0 * ((double) k) / (n - 1);
            double theta = Math.acos(h);
            spiralSet.add(new Vector3d(Math.sin(theta) * Math.cos(phi.get(k)), Math
                    .sin(theta) * Math.sin(phi.get(k)), Math.cos(theta)));

        }

        return spiralSet;
    }

    private static double getPhiByRecursion(double n, double phiKMinus1,
                                            double hk) {
        double phiK = phiKMinus1 + 3.6 / Math.sqrt(n) * 1.0 / Math.sqrt(1 - hk *
                hk);
        // modulo 2pi calculation works for positive numbers only, which is not a
        // problem in this case.
        return phiK - Math.floor(phiK / (2 * Math.PI)) * 2 * Math.PI;
    }

    private static int estimateNSpiralPointsRequired(double searchRadius,
                                                     double pixelWidth) {
        return (int) Math.ceil(Math.pow(searchRadius * 3.809 / pixelWidth, 2));
    }

    Vector3d findFirstPointInBGAlongRay(final Vector3d rayIncrement,
                                        final Vector3d start) {
        RandomAccess<R> randomAccess = inputImage.randomAccess();

        Vector3d currentRealPosition = new Vector3d(start);
        long[] currentPixelPosition = vectorToPixelGrid(start);
        randomAccess.setPosition(currentPixelPosition);

        while (randomAccess.get().getRealDouble() > 0) {
            currentRealPosition.add(rayIncrement);
            currentPixelPosition = vectorToPixelGrid(currentRealPosition);
            if (!isInBounds(currentPixelPosition)) break;
            randomAccess.setPosition(currentPixelPosition);
        }
        return currentRealPosition;
    }


    private boolean isInBounds(long[] currentPixelPosition) {
        long width = inputImage.dimension(0);
        long height = inputImage.dimension(1);
        long depth = inputImage.dimension(2);
        return !(currentPixelPosition[0] < 0 || currentPixelPosition[0] >= width ||
                currentPixelPosition[1] < 0 || currentPixelPosition[1] >= height ||
                currentPixelPosition[2] < 0 || currentPixelPosition[2] >= depth);
    }

    private static long[] vectorToPixelGrid(Vector3d currentPosition) {
        return Stream.of(currentPosition.getX(), currentPosition.getY(),
                currentPosition.getZ()).mapToLong(x -> (long) x.doubleValue()).toArray();
    }

    static List<List<ValuePair<Vector3d,Vector3d>>> getAllCombosOfThree(List<ValuePair<Vector3d,Vector3d>> points){
        final List<List<ValuePair<Vector3d,Vector3d>>> combos = new ArrayList<>();
        for(int i = 0; i<points.size()-2; i++)
            for(int j = i+1; j<points.size()-1; j++)
                for(int k = j+1; k<points.size(); k++)
                    combos.add(Arrays.asList(points.get(i),points.get(j),points.get(k)));
        return combos;
    }

    static List<ValuePair<List<ValuePair<Vector3d,Vector3d>>,Vector3d>> getAllCombosOfFour(List<ValuePair<Vector3d,Vector3d>> points, Vector3d centre){
        final Iterator<int[]> iterator = CombinatoricsUtils.combinationsIterator(points.size(), 4);
        final List<ValuePair<List<ValuePair<Vector3d,Vector3d>>,Vector3d>> combos = new ArrayList<>();
        iterator.forEachRemaining(el -> combos.add(
                new ValuePair<>(Arrays.asList(points.get(el[0]), points.get(el[1]), points.get(el[2]), points.get(el[3])), centre)));
        return combos;
    }

    @SuppressWarnings("unused")
    private void validateImage() {
        if (inputImage == null) {
            cancel(NO_IMAGE_OPEN);
            return;
        }
        if (AxisUtils.countSpatialDimensions(inputImage) != 3) {
            cancel(NOT_3D_IMAGE);
            return;
        }
        if (!ElementUtil.isColorsBinary(inputImage)) {
            cancel(NOT_BINARY);
            return;
        }
        if (!isCalibrationIsotropic() && !calibrationWarned) {
            final DialogPrompt.Result result = uiService.showDialog(
                    "The voxels in the image are anisotropic, which may affect results. Continue anyway?",
                    WARNING_MESSAGE, OK_CANCEL_OPTION);
            // Avoid showing warning more than once (validator gets called before and
            // after dialog pops up..?)
            calibrationWarned = true;
            if (result != OK_OPTION) {
                cancel(null);
            }
        }
    }

    // TODO Refactor into a static utility method with unit tests
    private boolean isCalibrationIsotropic() {
        final Optional<String> commonUnit = getSpatialUnit(inputImage, unitService);
        if (!commonUnit.isPresent()) {
            return false;
        }
        final String unit = commonUnit.get();
        return spatialAxisStream(inputImage).map(axis -> unitService.value(axis
                .averageScale(0, 1), axis.unit(), unit)).distinct().count() == 1;
    }
    // endregion
}