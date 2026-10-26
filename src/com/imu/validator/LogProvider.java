package com.imu.validator;
import android.content.*; import android.database.*; import android.net.Uri; import android.os.ParcelFileDescriptor; import android.provider.OpenableColumns; import java.io.*;
/** Narrow read-only provider, temporary URI grants via Android Sharesheet. */
public final class LogProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 private File file(Uri u) throws FileNotFoundException {
  try { File dir=new File(getContext().getFilesDir(),"sessions").getCanonicalFile(); File f=new File(dir,u.getLastPathSegment()).getCanonicalFile(); if(!dir.equals(f.getParentFile())||!f.isFile()||!f.getName().endsWith(".jsonl")) throw new IOException(); return f; }
  catch(IOException e){throw new FileNotFoundException("Invalid session URI");}
 }
 public String getType(Uri u){return "application/x-ndjson";}
 public ParcelFileDescriptor openFile(Uri u,String mode) throws FileNotFoundException { if(!"r".equals(mode)) throw new FileNotFoundException("Read only"); return ParcelFileDescriptor.open(file(u),ParcelFileDescriptor.MODE_READ_ONLY); }
 public Cursor query(Uri u,String[] p,String s,String[] a,String sort){ try {File f=file(u); MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}); c.addRow(new Object[]{f.getName(),f.length()}); return c;}catch(Exception e){return null;} }
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();} public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();} public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
