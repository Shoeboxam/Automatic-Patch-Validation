//
// TestTools.java
//

/*
LOCI software automated test suite for TestNG. Copyright (C) 2007-@year@
Melissa Linkert and Curtis Rueden. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
  * Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
  * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
  * Neither the name of the UW-Madison LOCI nor the names of its
    contributors may be used to endorse or promote products derived from
    this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE UW-MADISON LOCI ``AS IS'' AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package loci.tests.testng;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.FieldPosition;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import loci.common.DataTools;
import loci.common.DateTools;
import loci.common.Location;
import loci.formats.IFormatReader;
import loci.formats.IFormatWriter;
import loci.formats.ImageReader;

import org.apache.log4j.Level;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.WriterAppender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods for use with TestNG tests.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://trac.openmicroscopy.org.uk/ome/browser/bioformats.git/components/test-suite/src/loci/tests/testng/TestTools.java">Trac</a>,
 * <a href="http://git.openmicroscopy.org/?p=bioformats.git;a=blob;f=components/test-suite/src/loci/tests/testng/TestTools.java;hb=HEAD">Gitweb</a></dd></dl>
*/
public class TestTools {

  // -- Constants --

  private static final Logger LOGGER = LoggerFactory.getLogger(TestTools.class);

  public static final String DIVIDER =
    "----------------------------------------";

  /** Gets a timestamp for the current moment. */
  public static String timestamp() {
    return DateTools.convertDate(System.currentTimeMillis(), DateTools.UNIX);
  }

  /** Calculate the SHA-1 of a byte array. */
  public static String sha1(byte[] b, int offset, int len) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      md.reset();
      md.update(b, offset, len);
      byte[] digest = md.digest();
      return DataTools.bytesToHex(digest);
    }
    catch (NoSuchAlgorithmException e) { }
    return null;
  }

  /** Calculate the SHA-1 of a byte array. */
  public static String sha1(byte[] b) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      md.reset();
      md.update(b);
      byte[] digest = md.digest();
      return DataTools.bytesToHex(digest);
    }
    catch (NoSuchAlgorithmException e) { }
    return null;
  }

  /** Calculate the MD5 of a byte array. */
  public static String md5(byte[] b, int sizeX, int sizeY, int posX, int posY,
                           int width, int height, int bpp) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      md.reset();
      int offset = 0;
      for (int i = 0; i < height; i++) {
        offset = (((posY + i) * sizeX) + posX) * bpp;
        md.update(b, offset, width * bpp);
      }
      byte[] digest = md.digest();
      return DataTools.bytesToHex(digest);
    }
    catch (NoSuchAlgorithmException e) { }
    return null;
  }

  /** Calculate the MD5 of a byte array. */
  public static String md5(byte[] b, int offset, int len) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      md.reset();
      md.update(b, offset, len);
      byte[] digest = md.digest();
      return DataTools.bytesToHex(digest);
    }
    catch (NoSuchAlgorithmException e) { }
    return null;
  }

  /** Calculate the MD5 of a byte array. */
  public static String md5(byte[] b) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      md.reset();
      md.update(b);
      byte[] digest = md.digest();
      return DataTools.bytesToHex(digest);
    }
    catch (NoSuchAlgorithmException e) { }
    return null;
  }

  /** Returns true if a byte buffer of the given size will fit in memory. */
  public static boolean canFitInMemory(long bufferSize) {
    Runtime r = Runtime.getRuntime();
    long mem = r.freeMemory() / 2;
    return bufferSize < mem;
  }

  /** Gets the quantity of used memory, in MB. */
  public static long getUsedMemory() {
    Runtime r = Runtime.getRuntime();
    long mem = r.totalMemory() - r.freeMemory();
    return mem >> 20;
  }

  /** Gets the class name sans package for the given object. */
  public static String shortClassName(Object o) {
    String name = o.getClass().getName();
    int dot = name.lastIndexOf(".");
    return dot < 0 ? name : name.substring(dot + 1);
  }

  /** Creates a new log file. */
  public static void createLogFile() {
    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
    StringBuffer dateBuf = new StringBuffer();
    fmt.format(new Date(), dateBuf, new FieldPosition(0));
    String logFile = "loci-software-test-" + dateBuf + ".log";
    LOGGER.info("Output logged to {}", logFile);
    try {
      org.apache.log4j.Logger root = org.apache.log4j.Logger.getRootLogger();
      root.setLevel(Level.INFO);
      root.addAppender(new WriterAppender(
        new PatternLayout("%p [%d{dd-MM-yyyy HH:mm:ss.SSS}] %m%n"),
        new PrintWriter(new FileOutputStream(logFile))));
    }
    catch (IOException e) { LOGGER.info("", e); }

    // close log file on exit
    Runtime.getRuntime().addShutdownHook(new Thread() {
      public void run() {
        LOGGER.info(DIVIDER);
        LOGGER.info("Test suite complete.");
      }
    });
  }

  /** Recursively generate a list of files to test. */
  public static void getFiles(String root, List files,
    final ConfigurationTree config)
  {
    Location f = new Location(root);
    String[] subs = f.list();
    if (subs == null) subs = new String[0];

    // make sure that if a config file exists, it is first on the list
    for (int i=0; i<subs.length; i++) {
      Location file = new Location(root, subs[i]);
      subs[i] = file.getAbsolutePath();
      if (subs[i].endsWith(".bioformats")) {
        String tmp = subs[0];
        subs[0] = subs[i];
        subs[i] = tmp;

        // special config file for the test suite
        LOGGER.info("\tconfig file");
        try {
          config.parseConfigFile(subs[0]);
        }
        catch (IOException exc) {
          LOGGER.info("", exc);
        }
        catch (Exception e) { }
      }
    }

    Arrays.sort(subs, new Comparator() {
      public int compare(Object o1, Object o2) {
        String s1 = o1.toString();
        String s2 = o2.toString();

        Configuration c1 = null;
        Configuration c2 = null;

        try {
          c1 = config.get(s1);
        }
        catch (IOException e) { }
        try {
          c2 = config.get(s2);
        }
        catch (IOException e) { }

        if (c1 == null && c2 != null) {
          return 1;
        }
        else if (c1 != null && c2 == null) {
          return -1;
        }

        return s1.compareTo(s2);
      }
    });

    ImageReader typeTester = new ImageReader();

    for (int i=0; i<subs.length; i++) {
      Location file = new Location(subs[i]);
      LOGGER.info("Checking {}:", subs[i]);

      if (file.getName().equals(".bioformats")) {
        continue;
      }
      else if (isIgnoredFile(subs[i], config)) {
        LOGGER.info("\tignored");
        continue;
      }
      else if (file.isDirectory()) {
        LOGGER.info("\tdirectory");
        getFiles(subs[i], files, config);
      }
      else if (!subs[i].endsWith("readme.txt")) {
        if (typeTester.isThisType(subs[i])) {
          LOGGER.info("\tOK");
          files.add(file.getAbsolutePath());
        }
        else LOGGER.info("\tunknown type");
      }
      file = null;
    }
  }

  /** Determines if the given file should be ignored by the test suite. */
  public static boolean isIgnoredFile(String file, ConfigurationTree config) {
    if (file.indexOf(File.separator + ".") >= 0) return true; // hidden file

    try {
      Configuration c = config.get(file);
      if (c == null) return false;
      if (!c.doTest()) return true;
    }
    catch (IOException e) { }
    catch (Exception e) { }

    // HACK - heuristics to speed things up
    if (file.endsWith(".oif.files")) return true; // ignore .oif folders

    return false;
  }

  /**
   * Iterates over every tile in a given pixel buffer based on the over arching
   * dimensions and a requested maximum tile width and height.
   * @param iteration Invoker to call for each tile.
   * @param sizeX Width of the entire image.
   * @param sizeY Height of the entire image.
   * @param sizeZ Number of optical sections the image contains.
   * @param sizeC Number of channels the image contains.
   * @param sizeT Number of timepoints the image contains.
   * @param tileWidth <b>Maximum</b> width of the tile requested. The tile
   * request itself will be smaller than the original tile width requested if
   * <code>x + tileWidth > sizeX</code>.
   * @param tileHeight <b>Maximum</b> height of the tile requested. The tile
   * request itself will be smaller if <code>y + tileHeight > sizeY</code>.
   * @return The total number of tiles iterated over.
   */
  public static int forEachTile(TileLoopIteration iteration,
      int sizeX, int sizeY, int sizeZ, int sizeC,
      int sizeT, int tileWidth, int tileHeight)

  {
    int tileCount = 0;
    int x, y, w, h;
    for (int t = 0; t < sizeT; t++) {
      for (int c = 0; c < sizeC; c++) {
        for (int z = 0; z < sizeZ; z++) {
          for (int tileOffsetY = 0; 
               tileOffsetY < (sizeY + tileHeight - 1) / tileHeight;
               tileOffsetY++) {
            for (int tileOffsetX = 0;
                 tileOffsetX < (sizeX + tileWidth - 1) / tileWidth;
                 tileOffsetX++) {
              x = tileOffsetX * tileWidth;
              y = tileOffsetY * tileHeight;
              w = tileWidth;
              if (w + x > sizeX) {
                w = sizeX - x;
              }
              h = tileHeight;
              if (h + y > sizeY) {
                h = sizeY - y;
              }
              iteration.run(z, c, t, x, y, w, h, tileCount);
              tileCount++;
            }
          }
        }
      }
    }
    return tileCount;
  }

  /**
   * A single iteration of a tile for each loop.
   * @author Chris Allan <callan at blackcat dot ca>
   * @since OMERO Beta-4.3.0
   */
  public interface TileLoopIteration {
    /**
     * Invoke a single loop iteration.
     * @param z Z section counter of the loop.
     * @param c Channel counter of the loop.
     * @param t Timepoint counter of the loop.
     * @param x X offset within the plane specified by the section, channel and
     * timepoint counters.
     * @param y Y offset within the plane specified by the section, channel and
     * timepoint counters.
     * @param tileWidth Width of the tile requested. The tile request
     * itself may be smaller than the original tile width requested if
     * <code>x + tileWidth > sizeX</code>.
     * @param tileHeight Height of the tile requested. The tile request
     * itself may be smaller if <code>y + tileHeight > sizeY</code>.
     * @param tileCount Counter of the tile since the beginning of the loop.
     */
    void run(int z, int c, int t, int x, int y, int tileWidth,
             int tileHeight, int tileCount);
  }
}
