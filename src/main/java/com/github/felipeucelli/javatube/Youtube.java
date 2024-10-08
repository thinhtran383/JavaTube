package com.github.felipeucelli.javatube;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

import com.github.felipeucelli.javatube.exceptions.*;
import org.json.*;


public class Youtube {

    private final String urlVideo;
    private final String watchUrl;
    private InnerTube innerTube = null;
    private String client = null;
    private JSONObject vidInfo = null;
    private int poTokenAttempts = 1;
    private String html = null;
    private String js = null;
    private JSONObject ytCfg = null;
    private JSONObject signatureTimestamp = null;
    private String playerJs = null;

    /**
     * Default client: WEB
     * */
    public Youtube(String url) throws Exception {
        urlVideo = url;
        watchUrl = "https://www.youtube.com/watch?v=" + videoId();
        client = "ANDROID_TESTSUITE";
        innerTube = new InnerTube(client);
    }
    /**
     * @Clients:
     *          WEB,
     *          WEB_EMBED,
     *          WEB_MUSIC,
     *          WEB_CREATOR,
     *          WEB_SAFARI,
     *          MWEB,
     *          ANDROID,
     *          ANDROID_VR,
     *          ANDROID_MUSIC,
     *          ANDROID_CREATOR,
     *          ANDROID_TESTSUITE,
     *          ANDROID_PRODUCER,
     *          IOS,
     *          IOS_MUSIC,
     *          IOS_CREATOR,
     *          TV_EMBED,
     *          MEDIA_CONNECT
     * */
    public Youtube(String url, String clientName) throws Exception {
        client = clientName;
        innerTube = new InnerTube(client);
        urlVideo = url;
        watchUrl = "https://www.youtube.com/watch?v=" + videoId();
    }

    private String videoId() throws Exception {
        Pattern pattern = Pattern.compile("(?:v=|/)([0-9A-Za-z_-]{11}).*");
        Matcher matcher = pattern.matcher(urlVideo);
        if (matcher.find()) {
            return matcher.group(1);
        }else {
            throw new RegexMatchError("videoId: could not find match for " + pattern);
        }
    }

    @Override
    public String toString(){
        try {
            return "<com.github.felipeucelli.javatube.Youtube object: videoId=" + videoId() + ">";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String setHtml() throws Exception {
        Map<String, String> headers =  !client.contains("WEB") ? null : innerTube.getClientHeaders();
        return Request.get(watchUrl, headers).toString(StandardCharsets.UTF_8.name()).replace("\n", "");
    }

    public String getHtml() throws Exception {
        if(html == null){
            html = setHtml();
        }
        return html;
    }

    private String setSignatureTimestamp() throws Exception {
        String pattern = "signatureTimestamp:(\\d*)";
        Pattern regex = Pattern.compile(pattern);
        Matcher matcher = regex.matcher(getJs());
        if(matcher.find()){
            return matcher.group(1);
        }else {
            throw new RegexMatchError("setSignatureTimestamp: Unable to find signatureTimestamp in playerJs: " + getYtPlayerJs());
        }
    }
    public JSONObject getSignatureTimestamp() throws Exception {
        if(signatureTimestamp == null){
            signatureTimestamp =  new JSONObject(
                    "{" +
                                "\"playbackContext\": {" +
                                    "\"contentPlaybackContext\": {" +
                                        "\"signatureTimestamp\": " + setSignatureTimestamp() +
                                    "}" +
                                "}" +
                            "}"
            );
        }
        return signatureTimestamp;
    }

    private JSONObject setYtCfg() throws Exception {
        Pattern pattern = Pattern.compile("window\\.ytplayer=\\{};ytcfg\\.set\\((\\{.*?\\})\\);");
        Matcher matcher = pattern.matcher(getHtml());
        if(matcher.find()){
            return new JSONObject(matcher.group(1));
        }else {
            throw new RegexMatchError("setYtCfg: Could not find ytCfg: " + pattern);
        }
    }

    public JSONObject getYtCfg() throws Exception {
        if(ytCfg == null){
            ytCfg = setYtCfg();
        }
        return ytCfg;
    }

    private String setYtPlayerJs() throws Exception {
        Pattern pattern = Pattern.compile("(/s/player/[\\w\\d]+/[\\w\\d_/.\\-]+/base\\.js)");
        Matcher matcher = pattern.matcher(getHtml());
        if (matcher.find()) {
            return "https://youtube.com" + matcher.group(1);
        }else {
            throw new RegexMatchError("setYtPlayerJs: Could not find playerJs: " + pattern);
        }
    }
    public String getYtPlayerJs() throws Exception {
        if(playerJs == null){
            playerJs = setYtPlayerJs();
        }
        return playerJs;
    }

    private String setJs() throws Exception {
        return Request.get(getYtPlayerJs()).toString().replace("\n", "");
    }
    public String getJs() throws Exception {
        if(js == null){
            js = setJs();
        }
        return js;
    }

    public String getUrl(){
        return watchUrl;
    }

    private JSONObject setVidInfo() throws Exception {
        String pattern = "ytInitialPlayerResponse\\s=\\s(\\{\"responseContext\":.*?\\});(?:var|</script>)";
        Pattern regex = Pattern.compile(pattern);
        Matcher matcher = regex.matcher(getHtml());
        if(matcher.find()){
           return new JSONObject(matcher.group(1));
        }else {
            throw new RegexMatchError("setVidInfo: Unable to find video information: " + pattern);
        }
    }

    private JSONObject getVidInfo() throws Exception {
        if (vidInfo == null) {
            if (innerTube == null) {
                vidInfo = setVidInfo();
            } else {
                if (innerTube.getRequireJsPlayer()) {
                    innerTube.updateInnerTubeContext(innerTube.getInnerTubeContext(), getSignatureTimestamp());
                }
                vidInfo = innerTube.player(videoId());
            }
        }
        return vidInfo;
    }

    void checkPoToken() throws Exception {
        if(innerTube == null || client.contains("WEB")){
            // https://github.com/yt-dlp/yt-dlp/pull/10456
            List<String> poTokenExperiments = List.of("51217476", "51217102");

            String value = "";

            for (int i = 0; i < vidInfo.getJSONObject("responseContext").getJSONArray("serviceTrackingParams").length(); i++){
                JSONObject service = vidInfo.getJSONObject("responseContext").getJSONArray("serviceTrackingParams").getJSONObject(i);
                if (service.has("service")){
                    JSONArray params = service.getJSONArray("params");
                    for (int j = 0; j < params.length(); j++){
                        if (Objects.equals(params.getJSONObject(j).getString("key"), "e")){
                            value = params.getJSONObject(j).getString("value");
                            break;
                        }
                    }
                }
            }

            for (String po : poTokenExperiments){
                if (value.contains(po)){
                    if(poTokenAttempts <= 3){
                        System.out.print("API returned broken formats (poToken experiment detected). ");
                        System.out.println("Trying again " + poTokenAttempts + "/3.");
                        vidInfo = null;
                        poTokenAttempts += 1;
                        checkAvailability();
                        break;
                    }
                    System.out.println("Trying ANDROID_TESTSUITE client.");
                    innerTube = new InnerTube("ANDROID_TESTSUITE");
                    vidInfo = null;
                    break;
                }
            }
        }

    }

     void checkAvailability() throws Exception {
        JSONObject playabilityStatus = getVidInfo().getJSONObject("playabilityStatus");
        checkPoToken();
        String status = "";
        String reason = "";

        if (playabilityStatus.has("status")){
            status = playabilityStatus.getString("status");

            if (playabilityStatus.has("reason")){
                reason = playabilityStatus.getString("reason");

            } else if (playabilityStatus.has("messages")){
                reason = playabilityStatus.getJSONArray("messages").getString(0);
            }
        }

        switch (status) {
            case "UNPLAYABLE" -> {
                if (reason.equals("Join this channel to get access to members-only content like this video, and other exclusive perks.")) {
                    throw new MembersOnlyError(videoId());

                } else if (reason.equals("This live stream recording is not available.")){
                    throw new RecordingUnavailableError(videoId());

                } else {
                    throw new VideoUnavailableError(videoId());
                }
            }
            case "LOGIN_REQUIRED" -> {
                if (reason.equals("Sign in to confirm your age") || reason.equals("This video may be inappropriate for some users.")) {
                    throw new AgeRestrictedError(videoId());

                }else {
                    throw new VideoPrivateError(videoId());
                }
            }
            case "ERROR" -> {
                if (reason.equals("Video unavailable")) {
                    throw new VideoUnavailableError(videoId());
                }
            }
        }
        if (playabilityStatus.has("liveStreamability")){
            throw new LiveStreamError(videoId());
        }
    }

     JSONObject streamData() throws Exception {
        checkAvailability();
        return getVidInfo().getJSONObject("streamingData");
    }

    private String decodeURL(String s) throws UnsupportedEncodingException {
        return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
    }

    private static JSONArray applyDescrambler(JSONObject streamData) throws JSONException{
        JSONArray formats = new JSONArray();
        if(streamData.has("formats")){
            for(int i = 0; i < streamData.getJSONArray("formats").length(); i ++){
                formats.put(streamData.getJSONArray("formats").get(i));
            }
        }
        if(streamData.has("adaptiveFormats")){
            for(int i = 0; i < streamData.getJSONArray("adaptiveFormats").length(); i ++){
                formats.put(streamData.getJSONArray("adaptiveFormats").get(i));
            }
        }
        for(int i = 0; i < formats.length(); i++){
            if(formats.getJSONObject(i).has("signatureCipher")){
                String rawSig = formats.getJSONObject(i).getString("signatureCipher").replace("sp=sig", "");
                for(int j = 0; j < rawSig.split("&").length; j++){
                    if(Arrays.asList(rawSig.split("&")).get(j).startsWith("url")){
                        formats.getJSONObject(i).put("url", Arrays.asList(rawSig.split("&")).get(j).replace("url=", ""));
                    }else if(Arrays.asList(rawSig.split("&")).get(j).startsWith("s")){
                        formats.getJSONObject(i).put("s", Arrays.asList(rawSig.split("&")).get(j).replace("s=", ""));
                    }
                }
            }
        }
        return formats;
    }

    private ArrayList<Stream> fmtStreams() throws Exception {

        JSONArray streamManifest = applyDescrambler(streamData());

        ArrayList<Stream> fmtStream = new ArrayList<>();
        String title = getTitle();
        Stream video;

        if(innerTube == null || innerTube.getRequireJsPlayer()){
            applySignature(streamManifest);
        }
        for (int i = 0; streamManifest.length() > i; i++) {
            video = new Stream(streamManifest.getJSONObject(i), title);
            fmtStream.add(video);
        }
        return fmtStream;
    }

    private void applySignature(JSONArray streamManifest) throws Exception {
        Cipher cipher = new Cipher(getJs(), getYtPlayerJs());
        Pattern nSigPattern = Pattern.compile("&n=(.*?)&");
        Map<String, String> discoveredNSig = new HashMap<>();
        for (int i = 0; streamManifest.length() > i; i++) {
            if (streamManifest.getJSONObject(i).has("signatureCipher")) {
                String oldUrl = decodeURL(streamManifest.getJSONObject(i).getString("url"));
                streamManifest.getJSONObject(i).remove("url");
                String sig = streamManifest.getJSONObject(i).getString("s");
                streamManifest.getJSONObject(i).put("url", oldUrl + "&sig=" + cipher.getSignature(decodeURL(sig)));
            }

            String oldUrl = streamManifest.getJSONObject(i).getString("url");
            Matcher matcher = nSigPattern.matcher(oldUrl);
            if (matcher.find()) {
                String nSig = matcher.group(1);
                if(!discoveredNSig.containsKey(nSig)){
                    discoveredNSig.put(nSig, cipher.getNSig(nSig));
                }
                String newUrl = oldUrl.replaceFirst("&n=(.*?)&", "&n=" + discoveredNSig.get(nSig) + "&");
                streamManifest.getJSONObject(i).put("url", newUrl);
            }
        }
    }

    public String getTitle() throws Exception {
        return getVidInfo().getJSONObject("videoDetails")
                .getString("title");
    }

    public String getDescription() throws Exception {
        return getVidInfo().getJSONObject("videoDetails")
                .getString("shortDescription");
    }

    public String getPublishDate() throws Exception {
        Pattern pattern = Pattern.compile("(?<=itemprop=\"datePublished\" content=\")\\d{4}-\\d{2}-\\d{2}");
        Matcher matcher = pattern.matcher(getHtml());
        if (matcher.find()) {
            return matcher.group(0);
        }else {
            throw new RegexMatchError("getPublishDate: Unable to find publication date: " + pattern);
        }
    }

    public Integer length() throws Exception {
        return getVidInfo().getJSONObject("videoDetails")
                .getInt("lengthSeconds");
    }

    public String getThumbnailUrl() throws Exception {
        JSONArray thumbnails = new InnerTube("WEB").player(videoId()).getJSONObject("videoDetails")
                .getJSONObject("thumbnail")
                .getJSONArray("thumbnails");
        return thumbnails.getJSONObject(thumbnails.length() - 1).getString("url");
    }

    public Long getViews() throws Exception {
        return Long.parseLong(getVidInfo().getJSONObject("videoDetails")
                .getString("viewCount"));
    }

    public String getAuthor() throws Exception {
        return getVidInfo().getJSONObject("videoDetails")
                .getString("author");
    }

    public ArrayList<Captions> getCaptionTracks() throws Exception {
        try{
            JSONArray rawTracks = new InnerTube("WEB").player(videoId()).getJSONObject("captions")
                    .getJSONObject("playerCaptionsTracklistRenderer")
                    .getJSONArray("captionTracks");
            ArrayList<Captions> captions = new ArrayList<>();
            for(int i = 0; i < rawTracks.length(); i++){
                captions.add(new Captions(rawTracks.getJSONObject(i)));
            }
            return captions;
        } catch (JSONException e) {
            return null;
        }
    }

    public CaptionQuery getCaptions() throws Exception {
        return new CaptionQuery(getCaptionTracks());
    }

    public JSONArray getKeywords() throws Exception {
        try {
            return getVidInfo().getJSONObject("videoDetails")
                    .getJSONArray("keywords");
        }catch (JSONException e){
            return null;
        }
    }

    public StreamQuery streams() throws Exception {
        return new StreamQuery(fmtStreams());
    }
}