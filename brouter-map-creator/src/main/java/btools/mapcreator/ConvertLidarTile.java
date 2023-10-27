package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ConvertLidarTile {

  public static final boolean DEBUG = false;

  public static final short NODATA2 = -32767; // hgt-formats nodata
  public static final short NODATA = Short.MIN_VALUE;

  private static final String HGT_FILE_EXT = ".hgt";
  private static final int HGT_BORDER_OVERLAP = 1;
  private static final int HGT_3ASEC_ROWS = 1201; // 3 arc second resolution (90m)
  private static final int HGT_3ASEC_FILE_SIZE = HGT_3ASEC_ROWS * HGT_3ASEC_ROWS * Short.BYTES;
  private static final int HGT_1ASEC_ROWS = 3601; // 1 arc second resolution (30m)
  private static final int SRTM3_ROW_LENGTH = 1200; // number of elevation values per line
  private static final int SRTM1_ROW_LENGTH = 3600;
  private static final boolean SRTM_NO_ZERO = true;

  private int NROWS;
  private int NCOLS;
  private int ROW_LENGTH;
  private short[] imagePixels;


  public static void main(String[] args) throws Exception {
    if (args.length == 3 || args.length == 4 || args.length == 5) {
      String filename90 = args[0];
      if ("all".equals(filename90)) {
        //if (DEBUG)
        System.out.println("lidar convert all ");
        new ConvertLidarTile().doConvertAll(args[1], args[2], (args.length > 3 ? args[3] : null), (args.length == 5 ? args[4] : null));
        return;
      }
      // old filenames only
      String filename30 = filename90 + ".bef"; //filename90.substring(0, filename90.length() - 3) + "bef";

      int srtmLonIdx = Integer.parseInt(filename90.substring(5, 7).toLowerCase());
      int srtmLatIdx = Integer.parseInt(filename90.substring(8, 10).toLowerCase());

      int ilon_base = (srtmLonIdx - 1) * 5 - 180;
      int ilat_base = 150 - srtmLatIdx * 5 - 90;
      int row_length = SRTM3_ROW_LENGTH;
      String fallbackdir = null;
      if (args.length > 3) {
        row_length = (Integer.parseInt(args[3]) == 1 ? SRTM1_ROW_LENGTH : SRTM3_ROW_LENGTH);
        fallbackdir = (args.length == 5 ? args[4] : null);
      }
      //if (DEBUG)
      System.out.println("lidar convert " + ilon_base + " " + ilat_base + " from " + srtmLonIdx + " " + srtmLatIdx + " f: " + filename90 + " rowl " + row_length);

      new ConvertLidarTile().doConvert(args[1], ilon_base, ilat_base, args[2] + "/" + filename30, row_length, fallbackdir);
    } else {
      System.out.println("usage: java <srtm-filename> <hgt-data-dir> <srtm-output-dir> [arc seconds (1 or 3,default=3)] [hgt-fallback-data-dir]");
      System.out.println("or     java all <hgt-data-dir> <srtm-output-dir> [arc seconds (1 or 3, default=3)] [hgt-fallback-data-dir]");
      return;
    }
  }

  private void doConvertAll(String hgtdata, String outdir, String rlen, String hgtfallbackdata) throws Exception {
    int row_length = SRTM3_ROW_LENGTH;
    if (rlen != null) {
      row_length = (Integer.parseInt(rlen) == 1 ? SRTM1_ROW_LENGTH : SRTM3_ROW_LENGTH);
    }
    String filename30;
    for (int ilon_base = -180; ilon_base < 180; ilon_base += 5) {
      for (int ilat_base = 85; ilat_base > -90; ilat_base -= 5) {
        if (PosUnifier.UseLidarRd5FileName) {
          filename30 = genFilenameRd5(ilon_base, ilat_base);
        } else {
          filename30 = genFilenameOld(ilon_base, ilat_base);
        }
        if (DEBUG)
          System.out.println("lidar convert all: " + filename30);
        doConvert(hgtdata, ilon_base, ilat_base, outdir + "/" + filename30, row_length, hgtfallbackdata);
      }
    }
  }

  static String genFilenameOld(int ilon_base, int ilat_base) {
    int srtmLonIdx = ((ilon_base + 180) / 5) + 1;
    int srtmLatIdx = (60 - ilat_base) / 5;
    return String.format("srtm_%02d_%02d.bef", srtmLonIdx, srtmLatIdx);
  }

  static String genFilenameRd5(int ilon_base, int ilat_base) {
    return String.format("srtm_%s_%s.bef", ilon_base < 0 ? "W" + (-ilon_base) : "E" + ilon_base,
      ilat_base < 0 ? "S" + (-ilat_base) : "N" + ilat_base);
  }

  private void readHgtZip(String filename, int rowOffset, int colOffset, int row_length) throws Exception {
    ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(filename)));
    try {
      for (; ; ) {
        ZipEntry ze = zis.getNextEntry();
        if (ze == null) break;
        if (ze.getName().toLowerCase().endsWith(HGT_FILE_EXT)) {
          readHgtFromStream(zis, rowOffset, colOffset, row_length, 1);
          return;
        }
      }
    } finally {
      zis.close();
    }
  }

  private void readHgtFromStream(InputStream is, int rowOffset, int colOffset, int rowLength, int scale)
    throws Exception {
    DataInputStream dis = new DataInputStream(new BufferedInputStream(is));
    for (int ir = 0; ir < rowLength; ir++) {
      int row = rowOffset + ir * scale;

      for (int ic = 0; ic < rowLength; ic++) {
        int col = colOffset + ic * scale;

        int i1 = dis.read(); // msb first!
        int i0 = dis.read();

        if (i0 == -1 || i1 == -1)
          throw new RuntimeException("unexpected end of file reading hgt entry!");

        short val = (short) ((i1 << 8) | i0);

        if (val == NODATA2) {
          val = NODATA;
        }
        if (scale == 3) {
          setPixel(row, col, val);
          setPixel(row + 1, col, val);
          setPixel(row + 2, col, val);
          setPixel(row, col + 1, val);
          setPixel(row + 1, col + 1, val);
          setPixel(row + 2, col + 1, val);
          setPixel(row, col + 2, val);
          setPixel(row + 1, col + 2, val);
          setPixel(row + 2, col + 2, val);
        } else {
          setPixel(row, col, val);
        }
      }
    }
  }

  private void readFallbackFile(File file, int rowOffset, int colOffset, int row_length)
    throws Exception {
    int rowLength;
    int scale;
    if (file.length() > HGT_3ASEC_FILE_SIZE) {
      rowLength = HGT_1ASEC_ROWS;
      scale = 1;
    } else {
      rowLength = HGT_3ASEC_ROWS;
      scale = 3;
    }
    if (DEBUG)
      System.out.println("read fallback: " + file + " " + rowLength);

    FileInputStream fis = new FileInputStream(file);
    try {
      readHgtFromStream(fis, rowOffset, colOffset, rowLength, scale);
    } finally {
      fis.close();
    }
  }

  private void setPixel(int row, int col, short val) {
    if (row >= 0 && row < NROWS && col >= 0 && col < NCOLS) {
      imagePixels[row * NCOLS + col] = val;
    }
  }

  private short getPixel(int row, int col) {
    if (row >= 0 && row < NROWS && col >= 0 && col < NCOLS) {
      return imagePixels[row * NCOLS + col];
    }
    return NODATA;
  }


  public void doConvert(String inputDir, int lonDegreeStart, int latDegreeStart, String outputFile, int row_length, String hgtfallbackdata) throws Exception {
    int extraBorder = 0;

    List<String> foundList = new ArrayList<>();
    List<String> notfoundList = new ArrayList<>();

    boolean found = false;

    if (row_length == SRTM1_ROW_LENGTH) {
      // check for sources w/o border
      for (int latIdx = 0; latIdx < 5; latIdx++) {
        int latDegree = latDegreeStart + latIdx;

        for (int lonIdx = 0; lonIdx < 5; lonIdx++) {
          int lonDegree = lonDegreeStart + lonIdx;

          String filename = inputDir + "/" + formatLat(latDegree) + formatLon(lonDegree) + ".zip";
          File f = new File(filename);
          if (f.exists() && f.length() > 0) {
            found = true;
            break;
          }
        }
      }
    } else {
      // ignore when srtm3
      found = true;
    }
    if (found) { // init when found
      NROWS = 5 * row_length + 1 + 2 * extraBorder;
      NCOLS = 5 * row_length + 1 + 2 * extraBorder;
      imagePixels = new short[NROWS * NCOLS]; // 650 MB !

      // prefill as NODATA
      Arrays.fill(imagePixels, NODATA);
    } else {
      if (DEBUG)
        System.out.println("none 1sec data: " + lonDegreeStart + " " + latDegreeStart);
      return;
    }

    for (int latIdx = -1; latIdx <= 5; latIdx++) {
      int latDegree = latDegreeStart + latIdx;
      int rowOffset = extraBorder + (4 - latIdx) * row_length;

      for (int lonIdx = -1; lonIdx <= 5; lonIdx++) {
        int lonDegree = lonDegreeStart + lonIdx;
        int colOffset = extraBorder + lonIdx * row_length;

        String filename = inputDir + "/" + formatLat(latDegree) + formatLon(lonDegree) + ".zip";
        File f = new File(filename);
        if (f.exists() && f.length() > 0) {
          if (DEBUG)
            System.out.println("exist: " + filename);
          readHgtZip(filename, rowOffset, colOffset, row_length + 1);
        } else {
          if (hgtfallbackdata != null) {
            String filenamehgt = hgtfallbackdata + "/" + formatLat(latDegree) + formatLon(lonDegree) + ".hgt";
            f = new File(filenamehgt);
            if (f.exists() && f.length() > 0) {
              readFallbackFile(f, rowOffset, colOffset, row_length + 1);
              /*if (imagePixels == null) {
                imagePixels = new short[NROWS * NCOLS];
                Arrays.fill(imagePixels, NODATA);
                found = true;
              }
              */
              /*
              int rowLength;
              int arcspace;
              if (f.length() > HGT_3ASEC_FILE_SIZE) {
                rowLength = HGT_1ASEC_ROWS;
                arcspace = 1;
              } else {
                rowLength = HGT_3ASEC_ROWS;
                arcspace = 3;
              }
              if (DEBUG)
                System.out.println("read fallback: " + f + " " + rowLength);

              FileInputStream fis = new FileInputStream(f);
              DataInputStream dis = new DataInputStream(new BufferedInputStream(fis));
              for (int ir = 0; ir < rowLength; ir++) {
                int row = rowOffset + ir * arcspace;

                for (int ic = 0; ic < rowLength; ic++) {
                  int col = colOffset + ic * arcspace;

                  int i1 = dis.read(); // msb first!
                  int i0 = dis.read();

                  if (i0 == -1 || i1 == -1)
                    throw new RuntimeException("unexpected end of file reading hgt entry!");

                  short val = (short) ((i1 << 8) | i0);

                  if (val == NODATA2) {
                    val = NODATA;
                  }
                  if (arcspace == 3) {
                    setPixel(row, col, val);
                    setPixel(row+1, col, val);
                    setPixel(row+2, col, val);
                    setPixel(row, col+1, val);
                    setPixel(row+1, col+1, val);
                    setPixel(row+2, col+1, val);
                    setPixel(row, col+2, val);
                    setPixel(row+1, col+2, val);
                    setPixel(row+2, col+2, val);
                  } else {
                    setPixel(row, col, val);
                  }
                }
              }
              fis.close();
              */
            } else {
              if (DEBUG)
                System.out.println("none : " + filename);
            }
          }

        }
      }
    }

    // post fill zero
    if (SRTM_NO_ZERO) {
      for (int row = 0; row < NROWS; row++) {
        for (int col = 0; col < NCOLS; col++) {
          if (imagePixels[row * NCOLS + col] == 0) imagePixels[row * NCOLS + col] = NODATA;
        }
      }
    }

    boolean halfCol5 = false; // no halfcol tiles in lidar data (?)


    SrtmRaster raster = new SrtmRaster();
    raster.nrows = NROWS;
    raster.ncols = NCOLS;
    raster.halfcol = halfCol5;
    raster.noDataValue = NODATA;
    raster.cellsize = 1. / row_length;
    raster.xllcorner = lonDegreeStart - (0.5 + extraBorder) * raster.cellsize;
    raster.yllcorner = latDegreeStart - (0.5 + extraBorder) * raster.cellsize;
    raster.eval_array = imagePixels;

    // encode the raster
    OutputStream os = new BufferedOutputStream(new FileOutputStream(outputFile));
    new RasterCoder().encodeRaster(raster, os);
    os.close();

    // decode the raster
    InputStream is = new BufferedInputStream(new FileInputStream(outputFile));
    SrtmRaster raster2 = new RasterCoder().decodeRaster(is);
    is.close();

    short[] pix2 = raster2.eval_array;
    if (pix2.length != imagePixels.length)
      throw new RuntimeException("length mismatch!");

    // compare decoding result
    for (int row = 0; row < NROWS; row++) {
      int colstep = halfCol5 ? 2 : 1;
      for (int col = 0; col < NCOLS; col += colstep) {
        int idx = row * NCOLS + col;
        short p2 = pix2[idx];
        if (p2 != imagePixels[idx]) {
          throw new RuntimeException("content mismatch: p2=" + p2 + " p1=" + imagePixels[idx]);
        }
      }
    }
    imagePixels = null;
  }

  private static String formatLon(int lon) {
    if (lon >= 180)
      lon -= 180; // TODO: w180 oder E180 ?

    String s = "E";
    if (lon < 0) {
      lon = -lon;
      s = "W";
    }
    String n = "000" + lon;
    return s + n.substring(n.length() - 3);
  }

  private static String formatLat(int lat) {
    String s = "N";
    if (lat < 0) {
      lat = -lat;
      s = "S";
    }
    String n = "00" + lat;
    return s + n.substring(n.length() - 2);
  }

  public SrtmRaster getRaster(File f, double lon, double lat) throws Exception {
    long fileSize;
    InputStream inputStream;

    if (f.getName().toLowerCase().endsWith(".zip")) {
      ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(f)));
      for (; ; ) {
        ZipEntry ze = zis.getNextEntry();
        if (ze == null) {
          throw new FileNotFoundException(f.getName() + " doesn't contain a " + HGT_FILE_EXT + " file.");
        }
        if (ze.getName().toLowerCase().endsWith(HGT_FILE_EXT)) {
          fileSize = ze.getSize();
          inputStream = zis;
          break;
        }
      }
    } else {
      fileSize = f.length();
      inputStream = new FileInputStream(f);
    }

    int rowLength;
    if (fileSize > HGT_3ASEC_FILE_SIZE) {
      rowLength = HGT_1ASEC_ROWS;
    } else {
      rowLength = HGT_3ASEC_ROWS;
    }

    // stay at 1 deg * 1 deg raster
    NROWS = rowLength;
    NCOLS = rowLength;

    imagePixels = new short[NROWS * NCOLS];

    // prefill as NODATA
    Arrays.fill(imagePixels, NODATA);
    readHgtFromStream(inputStream, 0, 0, rowLength, 1);
    inputStream.close();

    SrtmRaster raster = new SrtmRaster();
    raster.nrows = NROWS;
    raster.ncols = NCOLS;
    raster.halfcol = false; // assume full resolution
    raster.noDataValue = NODATA;
    raster.cellsize = 1. / (double) (rowLength - HGT_BORDER_OVERLAP);
    raster.xllcorner = (int) (lon < 0 ? lon - 1 : lon); //onDegreeStart - raster.cellsize;
    raster.yllcorner = (int) (lat < 0 ? lat - 1 : lat); //latDegreeStart - raster.cellsize;
    raster.eval_array = imagePixels;

    return raster;
  }

}
