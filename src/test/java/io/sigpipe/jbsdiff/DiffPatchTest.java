/*
Copyright (c) 2013, Colorado State University
All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

This software is provided by the copyright holders and contributors "as is" and
any express or implied warranties, including, but not limited to, the implied
warranties of merchantability and fitness for a particular purpose are
disclaimed. In no event shall the copyright holder or contributors be liable for
any direct, indirect, incidental, special, exemplary, or consequential damages
(including, but not limited to, procurement of substitute goods or services;
loss of use, data, or profits; or business interruption) however caused and on
any theory of liability, whether in contract, strict liability, or tort
(including negligence or otherwise) arising in any way out of the use of this
software, even if advised of the possibility of such damage.
*/

package io.sigpipe.jbsdiff;

import io.sigpipe.jbsdiff.ui.FileUI;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

public class DiffPatchTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertRoundTrip(byte[] oldBytes, byte[] newBytes)
            throws CompressorException, InvalidHeaderException, IOException {
        ByteArrayOutputStream patchOut = new ByteArrayOutputStream();
        Diff.diff(oldBytes, newBytes, patchOut);

        ByteArrayOutputStream resultOut = new ByteArrayOutputStream();
        Patch.patch(oldBytes, patchOut.toByteArray(), resultOut);

        assertArrayEquals(newBytes, resultOut.toByteArray());
    }

    private byte[] randomBytes(long seed, int length) {
        byte[] data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    private byte[] mutate(byte[] src, int offset, int length, long seed) {
        byte[] result = Arrays.copyOf(src, src.length);
        Random rng = new Random(seed);
        for (int i = offset; i < offset + length; i++) {
            result[i] = (byte) rng.nextInt(256);
        }
        return result;
    }

    private byte[] insertBlock(byte[] src, int offset, byte[] block) {
        byte[] result = new byte[src.length + block.length];
        System.arraycopy(src, 0, result, 0, offset);
        System.arraycopy(block, 0, result, offset, block.length);
        System.arraycopy(src, offset, result, offset + block.length, src.length - offset);
        return result;
    }

    private byte[] deleteBlock(byte[] src, int offset, int length) {
        byte[] result = new byte[src.length - length];
        System.arraycopy(src, 0, result, 0, offset);
        System.arraycopy(src, offset + length, result, offset, src.length - offset - length);
        return result;
    }

    private File writeTemp(byte[] data) throws IOException {
        File f = tmp.newFile();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
        return f;
    }

    private byte[] readFile(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        try (DataInputStream dis = new DataInputStream(new FileInputStream(f))) {
            dis.readFully(data);
        }
        return data;
    }

    private boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    public void testBothEmpty() throws Exception {
        assertRoundTrip(new byte[0], new byte[0]);
    }

    @Test
    public void testEmptyOld() throws Exception {
        assertRoundTrip(new byte[0], randomBytes(1, 256));
    }

    @Test
    public void testEmptyNew() throws Exception {
        assertRoundTrip(randomBytes(2, 256), new byte[0]);
    }

    @Test
    public void testIdenticalFiles() throws Exception {
        byte[] data = randomBytes(3, 1024);
        assertRoundTrip(data, data);
    }

    @Test
    public void testSingleByteSame() throws Exception {
        assertRoundTrip(new byte[]{0x42}, new byte[]{0x42});
    }

    @Test
    public void testSingleByteDifferent() throws Exception {
        assertRoundTrip(new byte[]{0x00}, new byte[]{(byte) 0xFF});
    }

    @Test
    public void testAllSameByte() throws Exception {
        byte[] oldBytes = new byte[4096];
        byte[] newBytes = new byte[4096];
        Arrays.fill(newBytes, (byte) 0xAB);
        assertRoundTrip(oldBytes, newBytes);
    }

    // -------------------------------------------------------------------------
    // Round-trip: modification patterns
    // -------------------------------------------------------------------------

    @Test
    public void testModifyBeginning() throws Exception {
        byte[] old = randomBytes(10, 8192);
        assertRoundTrip(old, mutate(old, 0, 64, 11));
    }

    @Test
    public void testModifyEnd() throws Exception {
        byte[] old = randomBytes(12, 8192);
        assertRoundTrip(old, mutate(old, 8192 - 64, 64, 13));
    }

    @Test
    public void testModifyMiddle() throws Exception {
        byte[] old = randomBytes(14, 8192);
        assertRoundTrip(old, mutate(old, 4000, 256, 15));
    }

    @Test
    public void testScatteredBitFlips() throws Exception {
        byte[] old = randomBytes(16, 8192);
        byte[] newBytes = Arrays.copyOf(old, old.length);
        Random rng = new Random(17);
        for (int i = 0; i < 50; i++) {
            int pos = rng.nextInt(newBytes.length);
            newBytes[pos] ^= (byte) (1 << rng.nextInt(8));
        }
        assertRoundTrip(old, newBytes);
    }

    @Test
    public void testInsertBlock() throws Exception {
        byte[] old = randomBytes(18, 4096);
        assertRoundTrip(old, insertBlock(old, 2048, randomBytes(19, 512)));
    }

    @Test
    public void testDeleteBlock() throws Exception {
        byte[] old = randomBytes(20, 4096);
        assertRoundTrip(old, deleteBlock(old, 1024, 512));
    }

    @Test
    public void testRepeatedPattern() throws Exception {
        byte[] pattern = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        byte[] old = new byte[4096];
        for (int i = 0; i < old.length; i++) {
            old[i] = pattern[i % pattern.length];
        }
        byte[] newBytes = Arrays.copyOf(old, old.length);
        newBytes[2048] = 0x00;
        assertRoundTrip(old, newBytes);
    }

    @Test
    public void testLargeFiles() throws Exception {
        byte[] old = randomBytes(30, 512 * 1024);
        assertRoundTrip(old, mutate(old, 100_000, 50_000, 31));
    }

    @Test
    public void testNonBzip2CompressionRoundTrip() throws Exception {
        byte[] old = randomBytes(32, 4096);
        byte[] newBytes = mutate(old, 1000, 256, 33);

        ByteArrayOutputStream patchOut = new ByteArrayOutputStream();
        Diff.diff(old, newBytes, patchOut,
                new DefaultDiffSettings(CompressorStreamFactory.GZIP));

        ByteArrayOutputStream resultOut = new ByteArrayOutputStream();
        Patch.patch(old, patchOut.toByteArray(), resultOut);

        assertArrayEquals(newBytes, resultOut.toByteArray());
    }

    // -------------------------------------------------------------------------
    // File-based API
    // -------------------------------------------------------------------------

    @Test
    public void testFileApiRoundTrip() throws Exception {
        byte[] oldData = randomBytes(40, 4096);
        byte[] newData = mutate(oldData, 1000, 256, 41);

        File oldFile = writeTemp(oldData);
        File patchFile = tmp.newFile();
        File resultFile = tmp.newFile();

        ByteArrayOutputStream patchOut = new ByteArrayOutputStream();
        Diff.diff(oldData, newData, patchOut);
        try (FileOutputStream fos = new FileOutputStream(patchFile)) {
            fos.write(patchOut.toByteArray());
        }

        Patch.patch(oldFile, resultFile, patchFile);
        assertArrayEquals(newData, readFile(resultFile));
    }

    @Test
    public void testFileApiWithInsertion() throws Exception {
        byte[] oldData = randomBytes(50, 256 * 1024);
        byte[] newData = insertBlock(oldData, 100_000, randomBytes(51, 8192));

        File oldFile = writeTemp(oldData);
        File patchFile = tmp.newFile();
        File resultFile = tmp.newFile();

        ByteArrayOutputStream patchOut = new ByteArrayOutputStream();
        Diff.diff(oldData, newData, patchOut);
        try (FileOutputStream fos = new FileOutputStream(patchFile)) {
            fos.write(patchOut.toByteArray());
        }

        Patch.patch(oldFile, resultFile, patchFile);
        assertArrayEquals(newData, readFile(resultFile));
    }

    @Test
    public void testFileUiDiffAndPatch() throws Exception {
        byte[] oldData = randomBytes(55, 4096);
        byte[] newData = mutate(oldData, 512, 256, 56);

        File oldFile = writeTemp(oldData);
        File newFile = writeTemp(newData);
        File patchFile = tmp.newFile();
        File resultFile = tmp.newFile();

        FileUI.diff(oldFile, newFile, patchFile);
        FileUI.patch(oldFile, resultFile, patchFile);

        assertArrayEquals(newData, readFile(resultFile));
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test(expected = InvalidHeaderException.class)
    public void testInvalidMagicBytes() throws Exception {
        byte[] badPatch = new byte[32]; // all zeros, wrong magic
        Patch.patch(new byte[0], badPatch, new ByteArrayOutputStream());
    }

    @Test(expected = IOException.class)
    public void testPatchDiffBlockShorterThanControlClaims() throws Exception {
        CompressorStreamFactory csf = new CompressorStreamFactory();

        // Control block: claims 100 diff bytes, 0 extra
        ByteArrayOutputStream controlBuf = new ByteArrayOutputStream();
        try (OutputStream cs = csf.createCompressorOutputStream(
                CompressorStreamFactory.BZIP2, controlBuf)) {
            ControlBlock cb = new ControlBlock();
            cb.setDiffLength(100);
            cb.setExtraLength(0);
            cb.setSeekLength(0);
            cb.write(cs);
        }

        // Diff block: only 10 bytes (less than control block claims)
        ByteArrayOutputStream diffBuf = new ByteArrayOutputStream();
        try (OutputStream ds = csf.createCompressorOutputStream(
                CompressorStreamFactory.BZIP2, diffBuf)) {
            ds.write(new byte[10]);
        }

        // Extra block: empty
        ByteArrayOutputStream extraBuf = new ByteArrayOutputStream();
        try (OutputStream es = csf.createCompressorOutputStream(
                CompressorStreamFactory.BZIP2, extraBuf)) { }

        ByteArrayOutputStream patchOut = new ByteArrayOutputStream();
        new Header(controlBuf.size(), diffBuf.size(), 100).write(patchOut);
        patchOut.write(controlBuf.toByteArray());
        patchOut.write(diffBuf.toByteArray());
        patchOut.write(extraBuf.toByteArray());

        Patch.patch(new byte[0], patchOut.toByteArray(), new ByteArrayOutputStream());
    }

    @Test(expected = IOException.class)
    public void testPatchExtraBlockShorterThanControlClaims() throws Exception {
        CompressorStreamFactory csf = new CompressorStreamFactory();

        // Control block: claims 0 diff bytes, 100 extra bytes
        ByteArrayOutputStream controlBuf = new ByteArrayOutputStream();
        try (OutputStream cs = csf.createCompressorOutputStream(
                CompressorStreamFactory.BZIP2, controlBuf)) {
            ControlBlock cb = new ControlBlock();
            cb.setDiffLength(0);
            cb.setExtraLength(100);
            cb.setSeekLength(0);
            cb.write(cs);
        }

        // Diff block: empty
        ByteArrayOutputStream diffBuf = new ByteArrayOutputStream();
        try (OutputStream ds = csf.createCompressorOutputStream(
                CompressorStreamFactory.BZIP2, diffBuf)) { }

        // Extra block: only 10 bytes (less than control block claims)
        ByteArrayOutputStream extraBuf = new ByteArrayOutputStream();
        try (OutputStream es = csf.createCompressorOutputStream(
                CompressorStreamFactory.BZIP2, extraBuf)) {
            es.write(new byte[10]);
        }

        ByteArrayOutputStream patchOut = new ByteArrayOutputStream();
        new Header(controlBuf.size(), diffBuf.size(), 100).write(patchOut);
        patchOut.write(controlBuf.toByteArray());
        patchOut.write(diffBuf.toByteArray());
        patchOut.write(extraBuf.toByteArray());

        Patch.patch(new byte[0], patchOut.toByteArray(), new ByteArrayOutputStream());
    }

    @Test(expected = IOException.class)
    public void testOffsetOverflow() throws Exception {
        byte[] header = new byte[Header.HEADER_SIZE];
        System.arraycopy("BSDIFF40".getBytes("US-ASCII"), 0, header, 0, 8);
        // controlLength field occupies bytes 8-15; buf[3] = header[11] = 0x80
        // causes the accumulated int to overflow past Integer.MAX_VALUE
        header[11] = (byte) 0x80;
        Patch.patch(new byte[0], header, new ByteArrayOutputStream());
    }

    @Test
    public void testTruncatedPatch() throws Exception {
        byte[] old = randomBytes(60, 1024);
        byte[] newBytes = mutate(old, 0, 100, 61);

        ByteArrayOutputStream patchOut = new ByteArrayOutputStream();
        Diff.diff(old, newBytes, patchOut);
        // Truncate to just past the 32-byte header, into the compressed data
        byte[] truncated = Arrays.copyOf(patchOut.toByteArray(), 40);

        try {
            Patch.patch(old, truncated, new ByteArrayOutputStream());
            fail("Expected an exception for a truncated patch");
        } catch (IOException | CompressorException e) {
            // expected
        }
    }

    // -------------------------------------------------------------------------
    // Cross-compatibility (skipped if bsdiff/bspatch not installed)
    // -------------------------------------------------------------------------

    @Test
    public void testJbsdiffPatchAppliedByBspatch() throws Exception {
        Assume.assumeTrue("bspatch not found on PATH", commandExists("bspatch"));

        byte[] oldData = randomBytes(70, 8192);
        byte[] newData = mutate(oldData, 2000, 500, 71);

        File oldFile = writeTemp(oldData);
        File patchFile = tmp.newFile();
        File resultFile = tmp.newFile();

        ByteArrayOutputStream patchOut = new ByteArrayOutputStream();
        Diff.diff(oldData, newData, patchOut);
        try (FileOutputStream fos = new FileOutputStream(patchFile)) {
            fos.write(patchOut.toByteArray());
        }

        Process p = new ProcessBuilder(
                "bspatch",
                oldFile.getAbsolutePath(),
                resultFile.getAbsolutePath(),
                patchFile.getAbsolutePath()
        ).start();
        assertEquals("bspatch exited with non-zero status", 0, p.waitFor());
        assertArrayEquals(newData, readFile(resultFile));
    }

    @Test
    public void testNativeBsdiffPatchAppliedByJbsdiff() throws Exception {
        Assume.assumeTrue("bsdiff not found on PATH", commandExists("bsdiff"));

        byte[] oldData = randomBytes(80, 8192);
        byte[] newData = mutate(oldData, 3000, 500, 81);

        File oldFile = writeTemp(oldData);
        File newFile = writeTemp(newData);
        File patchFile = tmp.newFile();
        File resultFile = tmp.newFile();

        Process p = new ProcessBuilder(
                "bsdiff",
                oldFile.getAbsolutePath(),
                newFile.getAbsolutePath(),
                patchFile.getAbsolutePath()
        ).start();
        Assume.assumeTrue("bsdiff failed to run", p.waitFor() == 0);

        Patch.patch(oldFile, resultFile, patchFile);
        assertArrayEquals(newData, readFile(resultFile));
    }
}
