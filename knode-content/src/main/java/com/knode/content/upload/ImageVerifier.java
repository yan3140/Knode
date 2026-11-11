package com.knode.content.upload;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

public final class ImageVerifier {
    private static final byte[] PNG={(byte)0x89,80,78,71,13,10,26,10};
    private static final long MAX_PIXELS=4_194_304L;
    private static final Semaphore DECODE_SLOTS=new Semaphore(2);
    private ImageVerifier() {}

    public static String verify(byte[] bytes) {
        if (!DECODE_SLOTS.tryAcquire()) throw new UploadFailure(503,500);
        try { return verifyBounded(bytes); }
        finally { DECODE_SLOTS.release(); }
    }
    private static String verifyBounded(byte[] bytes) {
        boolean png=bytes.length>=8 && Arrays.equals(PNG,Arrays.copyOf(bytes,8));
        boolean jpeg=bytes.length>=4 && (bytes[0]&255)==255 && (bytes[1]&255)==216 &&
                (bytes[2]&255)==255 && (bytes[bytes.length-2]&255)==255 && (bytes[bytes.length-1]&255)==217;
        if (!png && !jpeg) throw invalid();
        if (png) verifyPngChunks(bytes);
        try (var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers=ImageIO.getImageReaders(input);
            ImageReader reader=null;
            while (readers.hasNext()) {
                ImageReader candidate=readers.next();
                if ("java.desktop".equals(candidate.getClass().getModule().getName())) { reader=candidate; break; }
                candidate.dispose();
            }
            if (reader==null) throw invalid();
            try {
                String format=reader.getFormatName();
                if (png ? !"PNG".equalsIgnoreCase(format) : !"JPEG".equalsIgnoreCase(format)) throw invalid();
                AtomicBoolean warning=new AtomicBoolean();
                reader.addIIOReadWarningListener((source,message) -> warning.set(true));
                reader.setInput(input,false,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if (width<1 || height<1 || width>4096 || height>4096 || (long)width*height>MAX_PIXELS) throw invalid();
                var types=reader.getImageTypes(0);
                if (!types.hasNext()) throw invalid();
                var sample=types.next().getSampleModel();
                int type=sample.getDataType();
                if (sample.getNumBands()>4 || (type!=DataBuffer.TYPE_BYTE && type!=DataBuffer.TYPE_USHORT && type!=DataBuffer.TYPE_INT))
                    throw invalid();
                BufferedImage decoded=reader.read(0);
                if (decoded==null) throw invalid();
                decoded.flush();
                if (warning.get()) throw invalid();
                return png?"image/png":"image/jpeg";
            } finally { reader.dispose(); }
        } catch (UploadFailure invalid) {
            throw invalid;
        } catch (IOException | RuntimeException invalid) {
            throw invalid();
        }
    }

    private static void verifyPngChunks(byte[] bytes) {
        int position=8, chunks=0;
        boolean data=false,ended=false;
        while (position<=bytes.length-12) {
            long length=unsignedInt(bytes,position);
            if (length>bytes.length-position-12L) throw invalid();
            int n=(int)length, type=position+4, payload=position+8, end=payload+n;
            String name=new String(bytes,type,4,java.nio.charset.StandardCharsets.US_ASCII);
            if (chunks==0 && (!"IHDR".equals(name) || n!=13)) throw invalid();
            if (chunks>0 && "IHDR".equals(name)) throw invalid();
            if ("IDAT".equals(name)) data=true;
            CRC32 crc=new CRC32(); crc.update(bytes,type,n+4);
            if (crc.getValue()!=unsignedInt(bytes,end)) throw invalid();
            position=end+4; chunks++;
            if ("IEND".equals(name)) {
                if (n!=0 || !data || position!=bytes.length) throw invalid();
                ended=true; break;
            }
        }
        if (!ended) throw invalid();
    }
    private static long unsignedInt(byte[] b,int p) {
        return ((long)(b[p]&255)<<24) | ((long)(b[p+1]&255)<<16) | ((long)(b[p+2]&255)<<8) | (b[p+3]&255);
    }
    private static UploadFailure invalid() { return new UploadFailure(200,507); }
}
