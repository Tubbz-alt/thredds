/* Copyright */
package ucar.nc2.ft2.coverage.remote;

import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ucar.nc2.*;
import ucar.nc2.constants.AxisType;
import ucar.nc2.ft2.coverage.*;
import ucar.nc2.stream.NcStream;
import ucar.nc2.stream.NcStreamProto;
import ucar.nc2.time.Calendar;
import ucar.nc2.time.CalendarDateRange;
import ucar.unidata.geoloc.LatLonPoint;
import ucar.unidata.geoloc.LatLonRect;
import ucar.unidata.geoloc.ProjectionRect;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.util.List;

/**
 * Server side for Cdmrf
 *
 * @author caron
 * @since 5/1/2015
 */
public class CdmrfWriter {
  static private final Logger logger = LoggerFactory.getLogger(CdmrfWriter.class);

  //  must start with this "CDFF"
  static public final byte[] MAGIC_START = new byte[]{0x43, 0x44, 0x46, 0x46};
  static public final int MAX_INLINE_NVALUES = 1000;
  private static final boolean show = false;

  public long sendHeader(OutputStream out, CoverageDataset gridDataset, String location) throws IOException {
    long size = 0;

    CdmrFeatureProto.CoverageDataset.Builder headerBuilder = encodeHeader(gridDataset, location);
    CdmrFeatureProto.CoverageDataset header = headerBuilder.build();

    // header message
    size += NcStream.writeBytes(out, NcStream.MAGIC_HEADER);
    byte[] b = header.toByteArray();
    size += NcStream.writeVInt(out, b.length); // len
    if (show) System.out.println("Write Header len=" + b.length);

    // payload
    size += NcStream.writeBytes(out, b);
    if (show) System.out.println(" header size=" + size);

    return size;
  }

  /* message CoverageDataset {
      required string name = 1;
      repeated Attribute atts = 2;
      required Rectangle latlonRect = 3;
      optional Rectangle projRect = 4;
      required CalendarDateRange timeRange = 5;

      repeated CoordSys coordSys = 6;
      repeated CoordTransform coordTransforms = 7;
      repeated CoordAxis coordAxes = 8;
      repeated Coverage grids = 9;
    } */
  CdmrFeatureProto.CoverageDataset.Builder encodeHeader(CoverageDataset gridDataset, String location) {
    CdmrFeatureProto.CoverageDataset.Builder builder = CdmrFeatureProto.CoverageDataset.newBuilder();
    builder.setName(location);
    builder.setCoverageType(convertCoverageType(gridDataset.getCoverageType()));
    builder.setDateRange(encodeDateRange(gridDataset.getCalendarDateRange()));
    if (gridDataset.getLatLonBoundingBox() != null)
      builder.setLatlonRect(encodeRectangle(gridDataset.getLatLonBoundingBox()));
    if (gridDataset.getProjBoundingBox() != null)
      builder.setProjRect(encodeRectangle(gridDataset.getProjBoundingBox()));

    for (Attribute att : gridDataset.getGlobalAttributes())
      builder.addAtts(NcStream.encodeAtt(att));

    for (CoverageCoordSys gcs : gridDataset.getCoordSys())
      builder.addCoordSys(encodeCoordSys(gcs));

    for (CoverageTransform gct : gridDataset.getCoordTransforms())
      builder.addCoordTransforms(encodeCoordTransform(gct));

    for (CoverageCoordAxis axis : gridDataset.getCoordAxes())
      builder.addCoordAxes(encodeCoordAxis(axis));

    for (Coverage grid : gridDataset.getCoverages())
      builder.addGrids(encodeGrid(grid));

    return builder;
  }

  /* message Rectangle {
      required double startx = 1;
      required double starty = 2;
      required double incx = 3;
      required double incy = 4;
    } */
  CdmrFeatureProto.Rectangle.Builder encodeRectangle(LatLonRect rect) {
    CdmrFeatureProto.Rectangle.Builder builder = CdmrFeatureProto.Rectangle.newBuilder();
    //     this(r.getLowerLeftPoint(), r.getUpperRightPoint().getLatitude() - r.getLowerLeftPoint().getLatitude(), r.getWidth());
    LatLonPoint ll = rect.getLowerLeftPoint();
    LatLonPoint ur = rect.getUpperRightPoint();
    builder.setStartx(ll.getLongitude());
    builder.setStarty(ll.getLatitude());
    builder.setIncx(rect.getWidth());
    builder.setIncy(ur.getLatitude() - ll.getLatitude());
    return builder;
  }

  CdmrFeatureProto.Rectangle.Builder encodeRectangle(ProjectionRect rect) {
    CdmrFeatureProto.Rectangle.Builder builder = CdmrFeatureProto.Rectangle.newBuilder();
    //      this(r.getMinX(), r.getMinY(), r.getMaxX(), r.getMaxY());
    builder.setStartx(rect.getMinX());
    builder.setStarty(rect.getMaxX());
    builder.setIncx(rect.getWidth());
    builder.setIncy(rect.getHeight());
    return builder;
  }

  /* message CalendarDateRange {
        required int64 start = 1;
        required int64 end = 2;
        required int32 calendar = 3; // calendar ordinal
      } */
  CdmrFeatureProto.CalendarDateRange.Builder encodeDateRange(CalendarDateRange dateRange) {
    CdmrFeatureProto.CalendarDateRange.Builder builder = CdmrFeatureProto.CalendarDateRange.newBuilder();

    builder.setStart(dateRange.getStart().getMillis());
    builder.setEnd(dateRange.getEnd().getMillis());
    Calendar cal = dateRange.getStart().getCalendar();
    builder.setCalendar(convertCalendar(cal));
    return builder;
  }

  /*
  message Coverage {
    required string name = 1; // short name
    required DataType dataType = 2;
    optional bool unsigned = 3 [default = false];
    repeated Attribute atts = 4;
    required string coordSys = 5;
  }
   */
  CdmrFeatureProto.Coverage.Builder encodeGrid(Coverage grid) {
    CdmrFeatureProto.Coverage.Builder builder = CdmrFeatureProto.Coverage.newBuilder();
    builder.setName(grid.getName());
    builder.setDataType(NcStream.convertDataType(grid.getDataType()));

    for (Attribute att : grid.getAttributes())
      builder.addAtts(NcStream.encodeAtt(att));

    builder.setUnits(grid.getUnits());
    builder.setDescription(grid.getDescription());
    builder.setCoordSys(grid.getCoordSysName());

    return builder;
  }

  /* message CoordSys {
    required string name = 1;               // must be unique in dataset's CoordSys
    repeated string axisNames = 2;
    repeated string transformNames = 3;
    optional CoverageType coverageType = 5;
  }
    } */
  CdmrFeatureProto.CoordSys.Builder encodeCoordSys(CoverageCoordSys gcs) {
    CdmrFeatureProto.CoordSys.Builder builder = CdmrFeatureProto.CoordSys.newBuilder();
    builder.setName(gcs.getName());
    builder.setCoverageType(convertCoverageType(gcs.getType()));

    for (String axis : gcs.getAxisNames())
      builder.addAxisNames(axis);

    for (String gct : gcs.getTransformNames())
      builder.addTransformNames(gct);
    return builder;
  }


  /* message CoordTransform {
      required bool isHoriz = 1;
      required string name = 2;
      repeated Attribute params = 3;
    } */
  CdmrFeatureProto.CoordTransform.Builder encodeCoordTransform(CoverageTransform gct) {
    CdmrFeatureProto.CoordTransform.Builder builder = CdmrFeatureProto.CoordTransform.newBuilder();
    builder.setIsHoriz(gct.isHoriz());
    builder.setName(gct.getName());
    for (Attribute att : gct.getAttributes())
      builder.addParams(NcStream.encodeAtt(att));
    return builder;
  }

  /* message CoordAxis {
      required string name = 1;
      required DataType dataType = 2;
      required int32 axisType = 3;    // ucar.nc2.constants.AxisType ordinal
      required int64 nvalues = 4;
      required string units = 5;
      optional bool isRegular = 6;
      required double min = 7;        // required ??
      required double max = 8;
      optional double resolution = 9;
    } */
  CdmrFeatureProto.CoordAxis.Builder encodeCoordAxis(CoverageCoordAxis axis) {
    CdmrFeatureProto.CoordAxis.Builder builder = CdmrFeatureProto.CoordAxis.newBuilder();
    builder.setName(axis.getName());
    builder.setDataType(NcStream.convertDataType(axis.getDataType()));
    builder.setAxisType(convertAxisType(axis.getAxisType()));
    builder.setNvalues(axis.getNcoords());
    if (axis.getUnits() != null) builder.setUnits(axis.getUnits());
    if (axis.getDescription() != null) builder.setDescription(axis.getDescription());
    builder.setDepend(convertDependenceType(axis.getDependenceType()));
    if (axis.getDependsOn() != null)
      builder.setDependsOn(axis.getDependsOn());

    for (Attribute att : axis.getAttributes())
      builder.addAtts(NcStream.encodeAtt(att));

    builder.setSpacing(convertSpacing(axis.getSpacing()));
    builder.setStartValue(axis.getStartValue());
    builder.setEndValue(axis.getEndValue());
    builder.setResolution(axis.getResolution());

    if (!axis.isRegular() && axis.getNcoords() < MAX_INLINE_NVALUES) {
      double[] values = axis.getValues();
      ByteBuffer bb = ByteBuffer.allocate(8 * values.length);
      DoubleBuffer db = bb.asDoubleBuffer();
      db.put(values);
      builder.setValues(ByteString.copyFrom(bb.array()));
    }

    return builder;
  }


  static public CdmrFeatureProto.AxisType convertAxisType(AxisType dtype) {
    switch (dtype) {
      case RunTime:
        return CdmrFeatureProto.AxisType.RunTime;
      case Ensemble:
        return CdmrFeatureProto.AxisType.Ensemble;
      case Time:
        return CdmrFeatureProto.AxisType.Time;
      case GeoX:
        return CdmrFeatureProto.AxisType.GeoX;
      case GeoY:
        return CdmrFeatureProto.AxisType.GeoY;
      case GeoZ:
        return CdmrFeatureProto.AxisType.GeoZ;
      case Lat:
        return CdmrFeatureProto.AxisType.Lat;
      case Lon:
        return CdmrFeatureProto.AxisType.Lon;
      case Height:
        return CdmrFeatureProto.AxisType.Height;
      case Pressure:
        return CdmrFeatureProto.AxisType.Pressure;
      case RadialAzimuth:
        return CdmrFeatureProto.AxisType.RadialAzimuth;
      case RadialDistance:
        return CdmrFeatureProto.AxisType.RadialDistance;
      case RadialElevation:
        return CdmrFeatureProto.AxisType.RadialElevation;
      case Spectral:
        return CdmrFeatureProto.AxisType.Spectral;
      case TimeOffset:
        return CdmrFeatureProto.AxisType.TimeOffset;
    }
    throw new IllegalStateException("illegal data type " + dtype);
  }


  static public CdmrFeatureProto.Calendar convertCalendar(Calendar type) {
    switch (type) {
      case gregorian:
        return CdmrFeatureProto.Calendar.gregorian;
      case proleptic_gregorian:
        return CdmrFeatureProto.Calendar.proleptic_gregorian;
      case noleap:
        return CdmrFeatureProto.Calendar.noleap;
      case all_leap:
        return CdmrFeatureProto.Calendar.all_leap;
      case uniform30day:
        return CdmrFeatureProto.Calendar.uniform30day;
      case julian:
        return CdmrFeatureProto.Calendar.julian;
      case none:
        return CdmrFeatureProto.Calendar.none;
    }
    throw new IllegalStateException("illegal data type " + type);
  }

    //   public enum Type {Coverage, Curvilinear, Grid, Swath, Fmrc}

  static public CdmrFeatureProto.CoverageType convertCoverageType(CoverageCoordSys.Type type) {
    switch (type) {
      case General:
        return CdmrFeatureProto.CoverageType.General;
      case Curvilinear:
        return CdmrFeatureProto.CoverageType.Curvilinear;
      case Grid:
        return CdmrFeatureProto.CoverageType.Grid;
      case Swath:
        return CdmrFeatureProto.CoverageType.Swath;
      case Fmrc:
        return CdmrFeatureProto.CoverageType.Fmrc;
    }
    throw new IllegalStateException("illegal CoverageType " + type);
  }

  static public CdmrFeatureProto.DependenceType convertDependenceType(CoverageCoordAxis.DependenceType type) {
    switch (type) {
      case independent:
        return CdmrFeatureProto.DependenceType.independent;
      case dependent:
        return CdmrFeatureProto.DependenceType.dependent;
      case scalar:
        return CdmrFeatureProto.DependenceType.scalar;
      case twoD:
        return CdmrFeatureProto.DependenceType.twoD;
    }
    throw new IllegalStateException("illegal data type " + type);
  }


  static public CdmrFeatureProto.AxisSpacing convertSpacing(CoverageCoordAxis.Spacing type) {
    switch (type) {
      case regular:
        return CdmrFeatureProto.AxisSpacing.regular;
      case irregularPoint:
        return CdmrFeatureProto.AxisSpacing.irregularPoint;
      case contiguousInterval:
        return CdmrFeatureProto.AxisSpacing.contiguousInterval;
      case discontiguousInterval:
        return CdmrFeatureProto.AxisSpacing.discontiguousInterval;
    }
    throw new IllegalStateException("illegal data type " + type);
  }


  ////////////////////////////////////////////////////////////////////////////////////////

  /*
  message GeoReferencedArray {
    required string gridName = 1;          // full escaped name.
    required DataType dataType = 2;
    optional bool bigend = 3 [default = true];
    optional uint32 version = 4 [default = 0];
    optional Compress compress = 5 [default = NONE];
    optional uint32 uncompressedSize = 6;

    repeated uint32 shape = 7;            // the shape of the returned array
    repeated string axisName = 8;         // each dimension corresponds to this axis
    required string coordSysName = 9;     // must have coordAxis corresponding to shape
  }

  message DataResponse {
    repeated CoordAxis coordAxes = 1;              // may be shared if asking for multiple grids
    repeated CoordSys coordSys = 2;                // may be shared if asking for multiple grids

    repeated GeoReferencedArray data = 4;
  }
   */

  public CdmrFeatureProto.DataResponse encodeDataResponse(Iterable<CoverageCoordAxis> axes, Iterable<CoverageCoordSys> coordSys, Iterable<CoverageTransform> transforms,
                                                          List<GeoReferencedArray> arrays, boolean deflate) {
    CdmrFeatureProto.DataResponse.Builder builder = CdmrFeatureProto.DataResponse.newBuilder();
    for (CoverageCoordAxis axis : axes)
      builder.addCoordAxes( encodeCoordAxis(axis));
    for (CoverageCoordSys cs : coordSys)
      builder.addCoordSys(encodeCoordSys(cs));
    for (CoverageTransform t : transforms)
      builder.addCoordTransforms(encodeCoordTransform(t));
    for (GeoReferencedArray array : arrays)
      builder.addGeoArray(encodeGeoReferencedArray(array, deflate));

    return builder.build();
  }

  public CdmrFeatureProto.GeoReferencedArray.Builder encodeGeoReferencedArray(GeoReferencedArray geoArray, boolean deflate) {
    CdmrFeatureProto.GeoReferencedArray.Builder builder = CdmrFeatureProto.GeoReferencedArray.newBuilder();
    builder.setCoverageName(geoArray.getCoverageName());
    builder.setDataType(NcStream.convertDataType( geoArray.getDataType()));
    builder.setVersion(1);

    if (deflate) {
      builder.setCompress(NcStreamProto.Compress.DEFLATE);
      long uncompressedSize = geoArray.getData().getSizeBytes();
      builder.setUncompressedSize(uncompressedSize);
    }

    int shape[] = geoArray.getData().getShape();
    for (int aShape : shape) builder.addShape(aShape);

    CoverageCoordSys csys = geoArray.getCoordSysForData();
    for (String axisName : csys.getAxisNames()) // geoArray.getAxisNames())  // LOOK could use csys.getAxisNames(), but order may be incorrrect, must match shape
      builder.addAxisName(axisName);

    builder.setCoordSysName(csys.getName());

    return builder;
  }
}