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
import java.util.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A private image collection or video, foreground-only scheduling, and a native crossfade overlay. */
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
    private ImageView imageView;
    private List<File> slides = new ArrayList<>();
    private int slideIndex;
    private final Runnable nextSlide = () -> { if (++slideIndex < slides.size()) loadSlide(generation); else finish(true); };
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
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->fitImage());
        cleanExcept(prefs.getString("files",prefs.getString("file","")));
    }
    private List<File> selectedFiles() {
        List<File> result=new ArrayList<>();
        for(String name:prefs.getString("files",prefs.getString("file","")).split(","))
            if(name.matches("media-[a-zA-Z0-9.-]+")) { File f=new File(directory,name);if(f.isFile())result.add(f); }
        return result;
    }
    private File selected() { List<File> files=selectedFiles();return files.isEmpty()?null:files.get(0); }
    private void cleanExcept(String keep) {
        Set<String> names=new HashSet<>(Arrays.asList(keep.split(",")));
        File[] files=directory.listFiles();
        if(files!=null)for(File f:files)if(f.isFile()&&!names.contains(f.getName())
            &&(f.getName().startsWith("media-")||f.getName().startsWith("pending-")))f.delete();
    }
    void mode(String value) { prefs.edit().putBoolean("random", "random".equals(value)).apply();host.changed(); }
    String status() {
        try {File file=selected();return new JSONObject().put("enabled",prefs.getBoolean("enabled",false))
            .put("interval",prefs.getInt("interval",600)).put("imageSeconds",prefs.getInt("imageSeconds",30))
            .put("hasMedia",file!=null && file.isFile()).put("kind",prefs.getString("kind",""))
            .put("bytes",file==null?0:file.length()).put("duration",prefs.getLong("duration",0))
            .put("count",selectedFiles().size()).put("mode",prefs.getBoolean("random",false)?"random":"sequence").put("importing",importing).toString();
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
        new AlertDialog.Builder(activity).setTitle("替换休息素材")
            .setMessage("图片与视频二选一。图片最多 10 张，每张不超过 20 MB；视频只选一个，不超过 200 MB 且不超过 1 分钟。\n\n建议素材与屏幕方向一致。同方向图片居中裁切铺满；方向不同时完整显示，空白区域为纯黑。视频仍裁切铺满。\n\n导入成功后替换整组旧素材，仅删除应用内副本。")
            .setNegativeButton("图片",(d,w)->openPicker(false)).setPositiveButton("视频",(d,w)->openPicker(true)).setNeutralButton("取消",null).show();
    }
    private void openPicker(boolean video) {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(video?"video/*":"image/*")
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,!video)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{activity.startActivityForResult(intent,PICK_REQUEST);}catch(Exception e){toast("找不到文件选择器");}
    }
    void result(int result,Intent data) {
        if(result!=Activity.RESULT_OK || data==null || importing || destroyed)return;
        List<Uri> uris=new ArrayList<>();
        if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());
        else if(data.getData()!=null)uris.add(data.getData());
        if(uris.isEmpty())return;
        if(uris.size()>10){toast("最多选择 10 张图片");return;}
        importing=true;int token=++importGeneration;finish(false);schedule();host.changed();
        worker.execute(()->{
            List<File> staged=new ArrayList<>();
            try {
                boolean video=false;long duration=0;int rotation=0;
                for(Uri uri:uris){
                    if(!"content".equals(uri.getScheme()))throw new IOException("无法读取文件");
                    String mime=activity.getContentResolver().getType(uri);
                    video=mime!=null&&mime.startsWith("video/");
                    MediaRestPolicy.validateCount(uris.size(),video);
                    if(!video&&(mime==null||!mime.startsWith("image/")))throw new IOException("请选择图片或视频");
                    File file=new File(directory,"media-"+UUID.randomUUID());staged.add(file);
                    long total=0,limit=video?MediaRestPolicy.VIDEO_LIMIT:MediaRestPolicy.IMAGE_LIMIT;
                    try(InputStream in=activity.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(file)){
                        if(in==null)throw new IOException("无法读取文件");
                        byte[] buffer=new byte[65536];int n;
                        while((n=in.read(buffer))!=-1){total+=n;if(total>limit)throw new IOException(video?"视频不能超过 200 MB":"每张图片不能超过 20 MB");if(Thread.currentThread().isInterrupted())throw new IOException("导入已取消");out.write(buffer,0,n);}
                    }
                    if(video){MediaMetadataRetriever r=new MediaMetadataRetriever();try{
                        r.setDataSource(file.getAbsolutePath());duration=Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                        String angle=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);rotation=angle==null?0:Integer.parseInt(angle);
                        if(Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))<=0)throw new IOException("没有视频画面");
                    }finally{r.release();}}
                    else{Bitmap probe=readImage(file);if(probe==null)throw new IOException("无法解码图片");probe.recycle();}
                    MediaRestPolicy.validate(video,total,duration);
                }
                final String kind=video?"video":"image";final long length=duration;final int angle=rotation;
                handler.post(()->{
                    if(destroyed||token!=importGeneration){for(File f:staged)f.delete();return;}
                    StringBuilder names=new StringBuilder();for(File f:staged){if(names.length()>0)names.append(',');names.append(f.getName());}
                    boolean saved=prefs.edit().putString("files",names.toString()).putString("file",staged.get(0).getName()).putString("kind",kind).putLong("duration",length).putInt("rotation",angle).commit();
                    if(saved){cleanExcept(names.toString());clock.reset(SystemClock.elapsedRealtime());toast("已保存并替换旧素材");}
                    else{for(File f:staged)f.delete();toast("保存失败，原素材已保留");}
                    importing=false;schedule();host.changed();
                });
            }catch(Exception|OutOfMemoryError e){
                for(File f:staged)f.delete();final String message=e.getMessage()==null?"无法导入素材":e.getMessage();
                handler.post(()->{if(destroyed||token!=importGeneration)return;importing=false;schedule();host.changed();toast(message);});
            }
        });
    }
    void gallery() {
        if(importing || destroyed)return;
        if(!"image".equals(prefs.getString("kind",""))){toast("请先导入图片");return;}
        ScrollView scroll=new ScrollView(activity);LinearLayout list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("已导入图片 · 横图 / 竖图").setView(scroll).setPositiveButton("关闭",null).create();
        List<Bitmap> thumbs=new ArrayList<>();dialog.setOnDismissListener(d->{list.removeAllViews();for(Bitmap b:thumbs)b.recycle();thumbs.clear();});dialog.show();
        for(File f:selectedFiles())worker.execute(()->{
            Bitmap decoded=null;try{decoded=readImage(f,320);}catch(Exception|OutOfMemoryError ignored){}
            final Bitmap image=decoded;handler.post(()->{
                if(!dialog.isShowing()||destroyed){if(image!=null)image.recycle();return;}
                if(image==null)return;thumbs.add(image);
                TextView label=new TextView(activity);label.setText(image.getWidth()>image.getHeight()?"横图":image.getWidth()<image.getHeight()?"竖图":"方图");list.addView(label);
                ImageView view=new ImageView(activity);view.setImageBitmap(image);view.setScaleType(ImageView.ScaleType.FIT_CENTER);
                list.addView(view,new LinearLayout.LayoutParams(-1,(int)(180*activity.getResources().getDisplayMetrics().density)));
            });
        });
    }
    void clear() {
        if(importing)return;
        new AlertDialog.Builder(activity).setTitle("移除休息媒体？").setMessage("仅删除应用内副本，相册原文件不会删除。")
            .setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->{
                finish(false);
                if(prefs.edit().remove("file").remove("files").remove("kind").remove("duration").putBoolean("enabled",false).commit()) cleanExcept("");
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
        }else {
            slides=selectedFiles();if(prefs.getBoolean("random",false))Collections.shuffle(slides);
            slideIndex=0;loadSlide(token);
        }
    }
    private void loadSlide(int token) {
        File file=slides.get(slideIndex);
        worker.execute(()->{
            Bitmap decoded=null;try{decoded=readImage(file);}catch(Exception|OutOfMemoryError ignored){}
            final Bitmap image=decoded;handler.post(()->{
                if(destroyed||generation!=token||!showing){if(image!=null)image.recycle();return;}
                if(image==null){fail("无法显示图片，请重新导入");return;}
                if(imageView!=null){imageView.animate().cancel();overlay.removeView(imageView);imageView.setImageDrawable(null);}
                if(bitmap!=null)bitmap.recycle();bitmap=image;
                imageView=new ImageView(activity);imageView.setImageBitmap(image);fitImage();
                overlay.addView(imageView,new FrameLayout.LayoutParams(-1,-1));
                imageView.setAlpha(animations?0:1);imageView.animate().alpha(1).setDuration(animations?500:0).start();reveal();
                handler.postDelayed(nextSlide,prefs.getInt("imageSeconds",30)*1000L);
            });
        });
    }
    private void fitImage(){
        if(imageView==null||bitmap==null)return;
        boolean opposite=MediaRestPolicy.fitImage(bitmap.getWidth(),bitmap.getHeight(),root.getWidth(),root.getHeight());
        imageView.setScaleType(opposite?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP);
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
        handler.removeCallbacks(end);handler.removeCallbacks(timeout);handler.removeCallbacks(nextSlide);
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
        imageView=null;texture=null;showing=false;fading=false;preview=false;
        View web=host.dashboard();if(web!=null){web.animate().cancel();web.setAlpha(1);}
        clock.reset(SystemClock.elapsedRealtime());schedule();host.playbackChanged();
    }
    private void releasePlayer(){
        if(player!=null){player.setOnCompletionListener(null);player.setOnErrorListener(null);player.setOnInfoListener(null);player.setOnPreparedListener(null);player.setOnVideoSizeChangedListener(null);player.release();player=null;}
        if(surface!=null){surface.release();surface=null;}
    }
    private void fail(String message){prefs.edit().putBoolean("enabled",false).apply();finish(false);host.changed();toast(message);}
    void resized(){cropVideo();root.post(this::fitImage);}
    void destroy(){destroyed=true;importGeneration++;finish(false);handler.removeCallbacksAndMessages(null);worker.shutdownNow();}
    private void toast(String value){Toast.makeText(activity,value,Toast.LENGTH_LONG).show();}
    private Bitmap readImage(File file) throws IOException {return readImage(file,2048);}
    private Bitmap readImage(File file,int maxSize) throws IOException {
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getAbsolutePath(),options);
        if(options.outWidth<=0 || options.outHeight<=0)throw new IOException("无法读取图片，请尝试 JPG/PNG");
        options.inSampleSize=1;while(options.outWidth/options.inSampleSize>maxSize || options.outHeight/options.inSampleSize>maxSize)options.inSampleSize*=2;
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
