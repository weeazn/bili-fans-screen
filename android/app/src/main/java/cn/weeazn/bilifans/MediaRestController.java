package cn.weeazn.bilifans;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.*;
import androidx.exifinterface.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A single private media asset, foreground-only scheduling, and a native crossfade overlay. */
final class MediaRestController {
    static final int PICK_REQUEST = 201;
    interface Host { View dashboard(); void changed(); void playbackChanged(); }
    private final Activity activity;
    private final FrameLayout root;
    private final Host host;
    private final SharedPreferences prefs;
    private final File directory;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final MediaRestPolicy.Clock clock = new MediaRestPolicy.Clock();
    private boolean foreground, dashboard, destroyed, preview, fading;
    private volatile boolean importing, showing;
    private boolean animations = true;
    private int generation, importGeneration;
    private FrameLayout overlay;
    private TextureView texture;
    private Bitmap bitmap;
    private MediaPlayer player;
    private Surface surface;
    private int videoWidth, videoHeight;
    private final Runnable trigger = () -> show(false);
    private final Runnable end = () -> finish(true);
    private final Runnable timeout = () -> fail("媒体无法播放，请尝试 JPG/PNG 图片或 H.264 MP4 视频");

    MediaRestController(Activity activity, FrameLayout root, Host host) {
        this.activity=activity;this.root=root;this.host=host;
        prefs=activity.getSharedPreferences("media-rest",0);
        directory=new File(activity.getNoBackupFilesDir(),"media-rest");
        directory.mkdirs();
        cleanExcept(prefs.getString("file",""));
    }
    private File selected() {
        String name=prefs.getString("file","");
        return name.matches("media-[a-zA-Z0-9.-]+") ? new File(directory,name) : null;
    }
    private void cleanExcept(String keep) {
        File[] files=directory.listFiles();
        if(files!=null) for(File f:files) if(f.isFile() && !f.getName().equals(keep)
            && (f.getName().startsWith("media-") || f.getName().startsWith("pending-"))) f.delete();
    }
    String status() {
        try {File file=selected();return new JSONObject().put("enabled",prefs.getBoolean("enabled",false))
            .put("interval",prefs.getInt("interval",600)).put("imageSeconds",prefs.getInt("imageSeconds",30))
            .put("hasMedia",file!=null && file.isFile()).put("kind",prefs.getString("kind",""))
            .put("bytes",file==null?0:file.length()).put("duration",prefs.getLong("duration",0))
            .put("importing",importing).toString();
        }catch(Exception e){return "{}";}
    }
    void configure(boolean enabled,int interval,int imageSeconds,boolean animate) {
        if(destroyed)return;
        boolean timingChanged=prefs.getInt("interval",600)!=MediaRestPolicy.interval(interval);
        prefs.edit().putBoolean("enabled",enabled).putInt("interval",MediaRestPolicy.interval(interval))
            .putInt("imageSeconds",MediaRestPolicy.imageSeconds(imageSeconds)).apply();
        animations=animate;
        if(timingChanged)clock.reset(SystemClock.elapsedRealtime());
        if(!enabled && showing)finish(false);
        schedule();host.changed();
    }
    void environment(boolean visible,boolean onDashboard) {
        foreground=visible;dashboard=onDashboard;
        if(showing && (!foreground || (!dashboard && !preview)))finish(false);
        schedule();
    }
    private void schedule() {
        handler.removeCallbacks(trigger);
        File file=selected();
        boolean run=!destroyed && foreground && dashboard && !showing && !importing
            && prefs.getBoolean("enabled",false) && file!=null && file.isFile();
        long now=SystemClock.elapsedRealtime();clock.running(run,now);
        if(run)handler.postDelayed(trigger,clock.remaining(prefs.getInt("interval",600),now));
    }
    void pick() {
        if(importing || destroyed)return;
        new AlertDialog.Builder(activity).setTitle("选择全屏休息媒体")
            .setMessage("图片或视频最好与手机显示方向和屏幕比例一致。非全屏素材会自动居中放大并裁剪铺满，边缘内容可能被裁掉。\n\n图片不超过 30 MB，默认展示 30 秒；视频不超过 200 MB 且不超过 1 分钟，静音播放一次。\n\n选好新素材并验证成功后会删除应用内旧副本，不会删除相册原文件。")
            .setNegativeButton("取消",null).setPositiveButton("选择图片或视频",(d,w)->{
                Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"})
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try{activity.startActivityForResult(intent,PICK_REQUEST);}catch(Exception e){toast("找不到相册或文件选择器");}
            }).show();
    }
    void result(int result,Intent data) {
        if(result!=Activity.RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData();
        if(!"content".equals(uri.getScheme()) || importing || destroyed)return;
        importing=true;int token=++importGeneration;finish(false);schedule();host.changed();
        worker.execute(()->{
            File pending=new File(directory,"pending-"+UUID.randomUUID());
            File accepted=null;
            try {
                String mime=activity.getContentResolver().getType(uri);
                boolean video=mime!=null && mime.startsWith("video/");
                if(!video && (mime==null || !mime.startsWith("image/")))throw new IOException("请选择图片或视频文件");
                long limit=video?MediaRestPolicy.VIDEO_LIMIT:MediaRestPolicy.IMAGE_LIMIT, total=0;
                try(InputStream in=activity.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(pending)) {
                    if(in==null)throw new IOException("无法读取文件");
                    byte[] buffer=new byte[65536];int n;
                    while((n=in.read(buffer))!=-1){total+=n;if(total>limit)throw new IOException(video?"视频不能超过 200 MB":"图片不能超过 30 MB");if(Thread.currentThread().isInterrupted())throw new IOException("已取消导入");out.write(buffer,0,n);}
                }
                long duration=0;int rotation=0;
                if(video){
                    MediaMetadataRetriever retriever=new MediaMetadataRetriever();
                    try {retriever.setDataSource(pending.getAbsolutePath());
                        duration=Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                        String angle=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                        rotation=angle==null?0:Integer.parseInt(angle);
                        if(Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))<=0)
                            throw new IOException("文件没有可播放的视频画面");
                    }finally{retriever.release();}
                }else{
                    Bitmap probe=readImage(pending);if(probe==null)throw new IOException("无法解码图片，请尝试 JPG 或 PNG");probe.recycle();
                }
                MediaRestPolicy.validate(video,total,duration);
                accepted=new File(directory,"media-"+UUID.randomUUID());
                if(!pending.renameTo(accepted))throw new IOException("保存素材失败，请检查可用空间");
                final File chosen=accepted;final long length=duration;final String kind=video?"video":"image";final int angle=rotation;
                handler.post(()->{
                    if(destroyed || token!=importGeneration){chosen.delete();return;}
                    // Commit the new valid asset before deleting the old one. A failed/cancelled pick preserves it.
                    boolean saved=prefs.edit().putString("file",chosen.getName()).putString("kind",kind).putLong("duration",length).putInt("rotation",angle).commit();
                    if(saved){cleanExcept(chosen.getName());clock.reset(SystemClock.elapsedRealtime());toast("素材已保存，已清理旧副本");}
                    else{chosen.delete();toast("保存失败，已保留原素材");}
                    importing=false;schedule();host.changed();
                });
            }catch(Exception | OutOfMemoryError e){
                pending.delete();if(accepted!=null)accepted.delete();
                final String message=e.getMessage()==null?"无法读取该媒体，请换一个文件":e.getMessage();
                handler.post(()->{if(destroyed || token!=importGeneration)return;importing=false;schedule();host.changed();toast(message);});
            }
        });
    }
    void clear() {
        if(importing)return;
        new AlertDialog.Builder(activity).setTitle("移除休息媒体？").setMessage("仅删除应用内副本，相册原文件不会删除。")
            .setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->{
                finish(false);
                if(prefs.edit().remove("file").remove("kind").remove("duration").putBoolean("enabled",false).commit()) cleanExcept("");
                else toast("移除失败，请检查可用空间");
                clock.reset(SystemClock.elapsedRealtime());schedule();host.changed();
            }).show();
    }
    boolean isShowing(){return showing;}
    void preview(){if(foreground)show(true);}
    private void show(boolean manual) {
        if(destroyed || importing || showing || !foreground || (!manual && !dashboard))return;
        File file=selected();if(file==null || !file.isFile()){toast("请先选择休息媒体");return;}
        preview=manual;showing=true;fading=false;int token=++generation;
        schedule();clock.reset(SystemClock.elapsedRealtime());
        overlay=new FrameLayout(activity);overlay.setBackgroundColor(Color.BLACK);overlay.setAlpha(0);
        overlay.setContentDescription("媒体休息，轻触返回看板");overlay.setOnClickListener(v->finish(true));
        root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));host.playbackChanged();
        handler.postDelayed(timeout,15000);
        if("video".equals(prefs.getString("kind",""))){
            texture=new TextureView(activity);overlay.addView(texture,new FrameLayout.LayoutParams(-1,-1));
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
                public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){prepareVideo(file,t,token);}
                public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){cropVideo();}
                public boolean onSurfaceTextureDestroyed(SurfaceTexture t){releasePlayer();return true;}
                public void onSurfaceTextureUpdated(SurfaceTexture t){}
            });
        }else worker.execute(()->{
            Bitmap decoded=null;try{decoded=readImage(file);}catch(Exception | OutOfMemoryError ignored){}
            final Bitmap image=decoded;
            handler.post(()->{
                if(destroyed || generation!=token || !showing){if(image!=null)image.recycle();return;}
                if(image==null){fail("无法显示图片，请更换素材");return;}
                bitmap=image;ImageView view=new ImageView(activity);view.setScaleType(ImageView.ScaleType.CENTER_CROP);view.setImageBitmap(image);
                overlay.addView(view,new FrameLayout.LayoutParams(-1,-1));reveal();
                handler.postDelayed(end,prefs.getInt("imageSeconds",30)*1000L);
            });
        });
    }
    private void prepareVideo(File file,SurfaceTexture target,int token){
        try{
            player=new MediaPlayer();surface=new Surface(target);player.setSurface(surface);
            player.setDataSource(file.getAbsolutePath());player.setVolume(0,0);player.setLooping(false);
            player.setOnPreparedListener(p->{
                if(token!=generation || !showing)return;
                videoSize(p.getVideoWidth(),p.getVideoHeight());
                if(p.getDuration()<=0 || p.getDuration()>60000){fail("视频时长不能超过 1 分钟");return;}
                p.start();handler.postDelayed(end,Math.min(60000,p.getDuration())+500L);
            });
            player.setOnInfoListener((p,what,extra)->{if(what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)reveal();return false;});
            player.setOnVideoSizeChangedListener((p,w,h)->videoSize(w,h));
            player.setOnCompletionListener(p->finish(true));
            player.setOnErrorListener((p,a,b)->{fail("该视频无法播放，请选择兼容的 H.264 MP4 文件");return true;});
            player.prepareAsync();
        }catch(Exception e){fail("无法打开视频，请重新选择素材");}
    }
    private void cropVideo(){
        if(texture==null)return;
        float[] scale=MediaRestPolicy.cover(videoWidth,videoHeight,texture.getWidth(),texture.getHeight());
        Matrix matrix=new Matrix();matrix.setScale(scale[0],scale[1],texture.getWidth()/2f,texture.getHeight()/2f);texture.setTransform(matrix);
    }
    private void videoSize(int width,int height){
        int rotation=prefs.getInt("rotation",0);
        // MediaPlayer/SurfaceTexture presents the rotation tag; match the displayed aspect ratio.
        boolean rotated=rotation==90 || rotation==270;
        videoWidth=rotated?height:width;videoHeight=rotated?width:height;cropVideo();
    }
    private void reveal(){
        if(!showing || fading)return;handler.removeCallbacks(timeout);
        long duration=animations?500:0;
        overlay.animate().alpha(1).setDuration(duration).start();
        View web=host.dashboard();if(web!=null)web.animate().alpha(0).setDuration(duration).start();
    }
    void finish(boolean animate){
        handler.removeCallbacks(end);handler.removeCallbacks(timeout);
        if(!showing)return;
        if(fading && animate)return;
        fading=true;generation++;
        if(player!=null){try{if(player.isPlaying())player.pause();}catch(IllegalStateException ignored){}}
        View web=host.dashboard();if(web!=null){web.animate().cancel();web.animate().alpha(1).setDuration(animate&&animations?500:0).start();}
        FrameLayout old=overlay;if(old!=null)old.animate().cancel();
        if(animate && animations && old!=null && foreground){old.animate().alpha(0).setDuration(500).withEndAction(this::removeOverlay).start();}
        else removeOverlay();
    }
    private void removeOverlay(){
        releasePlayer();
        if(overlay!=null){overlay.animate().cancel();root.removeView(overlay);overlay=null;}
        if(bitmap!=null){bitmap.recycle();bitmap=null;}
        texture=null;showing=false;fading=false;preview=false;
        View web=host.dashboard();if(web!=null){web.animate().cancel();web.setAlpha(1);}
        clock.reset(SystemClock.elapsedRealtime());schedule();host.playbackChanged();
    }
    private void releasePlayer(){
        if(player!=null){player.setOnCompletionListener(null);player.setOnErrorListener(null);player.setOnInfoListener(null);player.setOnPreparedListener(null);player.setOnVideoSizeChangedListener(null);player.release();player=null;}
        if(surface!=null){surface.release();surface=null;}
    }
    private void fail(String message){prefs.edit().putBoolean("enabled",false).apply();finish(false);host.changed();toast(message);}
    void resized(){cropVideo();}
    void destroy(){destroyed=true;importGeneration++;finish(false);handler.removeCallbacksAndMessages(null);worker.shutdownNow();}
    private void toast(String value){Toast.makeText(activity,value,Toast.LENGTH_LONG).show();}
    private Bitmap readImage(File file) throws IOException {
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getAbsolutePath(),options);
        if(options.outWidth<=0 || options.outHeight<=0)throw new IOException("无法读取图片，请尝试 JPG/PNG");
        options.inSampleSize=1;while(options.outWidth/options.inSampleSize>2048 || options.outHeight/options.inSampleSize>2048)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;Bitmap b=BitmapFactory.decodeFile(file.getAbsolutePath(),options);
        if(b==null)return null;
        int orientation=1;
        try{orientation=new ExifInterface(file.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(IOException ignored){}
        Matrix transform=new Matrix();
        switch(orientation){case 2:transform.setScale(-1,1);break;case 3:transform.setRotate(180);break;case 4:transform.setScale(1,-1);break;
            case 5:transform.setRotate(90);transform.postScale(-1,1);break;case 6:transform.setRotate(90);break;
            case 7:transform.setRotate(270);transform.postScale(-1,1);break;case 8:transform.setRotate(270);break;default:return b;}
        Bitmap rotated=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),transform,true);if(rotated!=b)b.recycle();return rotated;
    }
}
