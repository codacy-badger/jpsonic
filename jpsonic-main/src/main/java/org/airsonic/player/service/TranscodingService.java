/*
 This file is part of Airsonic.

 Airsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Airsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Airsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2016 (C) Airsonic Authors
 Based upon Subsonic, Copyright 2009 (C) Sindre Mehus
 */
package org.airsonic.player.service;

import org.airsonic.player.controller.VideoPlayerController;
import org.airsonic.player.dao.TranscodingDao;
import org.airsonic.player.domain.*;
import org.airsonic.player.io.TranscodeInputStream;
import org.airsonic.player.util.PlayerUtils;
import org.airsonic.player.util.StringUtil;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.PrefixFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static org.apache.commons.lang3.ObjectUtils.isEmpty;

/**
 * Provides services for transcoding media. Transcoding is the process of
 * converting a media file/stream to a different format and/or bit rate. The latter is
 * also called downsampling.
 *
 * @author Sindre Mehus
 * @see TranscodeInputStream
 */
@Service
public class TranscodingService {

    private static final Logger LOG = LoggerFactory.getLogger(TranscodingService.class);
    public static final String FORMAT_RAW = "raw";

    @Autowired
    private TranscodingDao transcodingDao;
    @Autowired
    private SettingsService settingsService;
    @Autowired
    @Lazy // used to deal with circular dependencies between PlayerService and TranscodingService
    private PlayerService playerService;

    /**
     * Returns all transcodings.
     *
     * @return Possibly empty list of all transcodings.
     */
    public List<Transcoding> getAllTranscodings() {
        return transcodingDao.getAllTranscodings();
    }

    /**
     * Returns all active transcodings for the given player. Only enabled transcodings are returned.
     *
     * @param player The player.
     * @return All active transcodings for the player.
     */
    public List<Transcoding> getTranscodingsForPlayer(Player player) {
        // FIXME - This should probably check isTranscodingInstalled()
        return transcodingDao.getTranscodingsForPlayer(player.getId());
    }

    /**
     * Sets the list of active transcodings for the given player.
     *
     * @param player         The player.
     * @param transcodingIds ID's of the active transcodings.
     */
    public void setTranscodingsForPlayer(Player player, int[] transcodingIds) {
        transcodingDao.setTranscodingsForPlayer(player.getId(), transcodingIds);
    }

    /**
     * Sets the list of active transcodings for the given player.
     *
     * @param player       The player.
     * @param transcodings The active transcodings.
     */
    public void setTranscodingsForPlayer(Player player, List<Transcoding> transcodings) {
        int[] transcodingIds = new int[transcodings.size()];
        for (int i = 0; i < transcodingIds.length; i++) {
            transcodingIds[i] = transcodings.get(i).getId();
        }
        setTranscodingsForPlayer(player, transcodingIds);
    }


    /**
     * Creates a new transcoding.
     *
     * @param transcoding The transcoding to create.
     */
    public void createTranscoding(Transcoding transcoding) {
        transcodingDao.createTranscoding(transcoding);

        // Activate this transcoding for all players?
        if (transcoding.isDefaultActive()) {
            for (Player player : playerService.getAllPlayers()) {
                if (player != null) {
                    List<Transcoding> transcodings = getTranscodingsForPlayer(player);
                    transcodings.add(transcoding);
                    setTranscodingsForPlayer(player, transcodings);
                }
            }
        }
    }

    /**
     * Deletes the transcoding with the given ID.
     *
     * @param id The transcoding ID.
     */
    public void deleteTranscoding(Integer id) {
        transcodingDao.deleteTranscoding(id);
    }

    /**
     * Updates the given transcoding.
     *
     * @param transcoding The transcoding to update.
     */
    public void updateTranscoding(Transcoding transcoding) {
        transcodingDao.updateTranscoding(transcoding);
    }

    /**
     * Returns whether transcoding is required for the given media file and player combination.
     *
     * @param mediaFile The media file.
     * @param player    The player.
     * @return Whether transcoding  will be performed if invoking the
     *         {@link #getTranscodedInputStream} method with the same arguments.
     */
    public boolean isTranscodingRequired(MediaFile mediaFile, Player player) {
        return getTranscoding(mediaFile, player, null, false) != null;
    }

    /**
     * Returns the suffix for the given player and media file, taking transcodings into account.
     *
     * @param player                The player in question.
     * @param file                  The media file.
     * @param preferredTargetFormat Used to select among multiple applicable transcodings. May be {@code null}.
     * @return The file suffix, e.g., "mp3".
     */
    public String getSuffix(Player player, MediaFile file, String preferredTargetFormat) {
        Transcoding transcoding = getTranscoding(file, player, preferredTargetFormat, false);
        return transcoding != null ? transcoding.getTargetFormat() : file.getFormat();
    }

    /**
     * Creates parameters for a possibly transcoded input stream for the given media file and player combination.
     * <p/>
     * A transcoding is applied if it is applicable for the format of the given file, and is activated for the
     * given player, and either the desired format or bitrate needs changing.
     * <p/>
     * Otherwise, a normal input stream to the original file is returned.
     *
     * @param mediaFile                The media file.
     * @param player                   The player.
     * @param maxBitRate               Overrides the per-player and per-user bitrate limit. May be {@code null}.
     * @param preferredTargetFormat    Used to select among multiple applicable transcodings. May be {@code null}.
     * @param videoTranscodingSettings Parameters used when transcoding video. May be {@code null}.
     * @return Parameters to be used in the {@link #getTranscodedInputStream} method.
     */
    public Parameters getParameters(MediaFile mediaFile, Player player, final Integer maxBitRate, String preferredTargetFormat,
                                    VideoTranscodingSettings videoTranscodingSettings) {

        Parameters parameters = new Parameters(mediaFile, videoTranscodingSettings);

        Integer mb = maxBitRate; 
        if (maxBitRate == null) {
            mb = TranscodeScheme.OFF.getMaxBitRate();
        }

        TranscodeScheme transcodeScheme = getTranscodeScheme(player).strictest(TranscodeScheme.fromMaxBitRate(mb));
        mb = transcodeScheme.getMaxBitRate();

        boolean hls = videoTranscodingSettings != null && videoTranscodingSettings.isHls();
        Transcoding transcoding = getTranscoding(mediaFile, player, preferredTargetFormat, hls);
        Integer bitRate = mediaFile.getBitRate();
        if (bitRate == null) {
            // Assume unlimited bitrate
            bitRate = TranscodeScheme.OFF.getMaxBitRate();
        }

        if (mediaFile.isVideo()) {
            if (mb == 0) {
                mb = VideoPlayerController.DEFAULT_BIT_RATE;
            }
        } else {
            if (mediaFile.isVariableBitRate()) {
                // Assume VBR needs approx 20% more bandwidth to maintain equivalent quality in CBR
                bitRate = bitRate * 6 / 5;
            }
            // Make sure bitrate is quantized to valid values for CBR
            if (TranscodeScheme.fromMaxBitRate(bitRate) != null) {
                bitRate = TranscodeScheme.fromMaxBitRate(bitRate).getMaxBitRate();
            }
        }

        if (mb == 0 || (bitRate != 0 && bitRate < mb)) {
            mb = bitRate;
        }

        if (transcoding != null && ((mb != 0 && (bitRate == 0 || bitRate > mb)) ||
            (preferredTargetFormat != null && ! mediaFile.getFormat().equalsIgnoreCase(preferredTargetFormat)))) {
            parameters.setTranscoding(transcoding);
        }

        parameters.setMaxBitRate(mb == 0 ? null : mb);
        parameters.setExpectedLength(getExpectedLength(parameters));
        parameters.setRangeAllowed(isRangeAllowed(parameters));
        return parameters;
    }

    /**
     * Returns a possibly transcoded or downsampled input stream for the given music file and player combination.
     * <p/>
     * A transcoding is applied if it is applicable for the format of the given file, and is activated for the
     * given player.
     * <p/>
     * If no transcoding is applicable, the file may still be downsampled, given that the player is configured
     * with a bit rate limit which is higher than the actual bit rate of the file.
     * <p/>
     * Otherwise, a normal input stream to the original file is returned.
     *
     * @param parameters As returned by {@link #getParameters}.
     * @return A possible transcoded or downsampled input stream.
     * @throws IOException If an I/O error occurs.
     */
    public InputStream getTranscodedInputStream(Parameters parameters) throws IOException {
        try {

            if (parameters.getTranscoding() != null) {
                return createTranscodedInputStream(parameters);
            }

        } catch (IOException x) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Transcoder failed: {}. Using original: " + parameters.getMediaFile().getFile().getAbsolutePath(), x.toString());
            }
        } catch (Exception x) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Transcoder failed. Using original: " + parameters.getMediaFile().getFile().getAbsolutePath(), x);
            }
        }
        return Files.newInputStream(Paths.get(parameters.getMediaFile().getFile().toURI()));
    }


    /**
     * Returns the strictest transcoding scheme defined for the player and the user.
     */
    private TranscodeScheme getTranscodeScheme(Player player) {
        String username = player.getUsername();
        if (username != null) {
            UserSettings userSettings = settingsService.getUserSettings(username);
            return player.getTranscodeScheme().strictest(userSettings.getTranscodeScheme());
        }

        return player.getTranscodeScheme();
    }

    /**
     * Returns an input stream by applying the given transcoding to the given music file.
     *
     * @param parameters Transcoding parameters.
     * @return The transcoded input stream.
     * @throws IOException If an I/O error occurs.
     */
    private InputStream createTranscodedInputStream(Parameters parameters)
            throws IOException {

        Transcoding transcoding = parameters.getTranscoding();
        Integer maxBitRate = parameters.getMaxBitRate();
        VideoTranscodingSettings videoTranscodingSettings = parameters.getVideoTranscodingSettings();
        MediaFile mediaFile = parameters.getMediaFile();

        if (!isEmpty(transcoding.getStep2()) && !isEmpty(transcoding.getStep3())) {
            return createTranscodeInputStream(transcoding.getStep3(), maxBitRate, videoTranscodingSettings, mediaFile,
                    createTranscodeInputStream(transcoding.getStep2(), maxBitRate, videoTranscodingSettings, mediaFile,
                            createTranscodeInputStream(transcoding.getStep1(), maxBitRate, videoTranscodingSettings, mediaFile, null)));
        } else if (!isEmpty(transcoding.getStep2())) {
            return createTranscodeInputStream(transcoding.getStep2(), maxBitRate, videoTranscodingSettings, mediaFile,
                    createTranscodeInputStream(transcoding.getStep1(), maxBitRate, videoTranscodingSettings, mediaFile, null));
        } else if (!isEmpty(transcoding.getStep3())) {
            return createTranscodeInputStream(transcoding.getStep3(), maxBitRate, videoTranscodingSettings, mediaFile,
                    createTranscodeInputStream(transcoding.getStep1(), maxBitRate, videoTranscodingSettings, mediaFile, null));
        }

        return createTranscodeInputStream(transcoding.getStep1(), maxBitRate, videoTranscodingSettings, mediaFile, null);
    }

    /**
     * Creates a transcoded input stream by interpreting the given command line string.
     * This includes the following:
     * <ul>
     * <li>Splitting the command line string to an array.</li>
     * <li>Replacing occurrences of "%s" with the path of the given music file.</li>
     * <li>Replacing occurrences of "%t" with the title of the given music file.</li>
     * <li>Replacing occurrences of "%l" with the album name of the given music file.</li>
     * <li>Replacing occurrences of "%a" with the artist name of the given music file.</li>
     * <li>Replacing occurrcences of "%b" with the max bitrate.</li>
     * <li>Replacing occurrcences of "%o" with the video time offset (used for scrubbing).</li>
     * <li>Replacing occurrcences of "%d" with the video duration (used for HLS).</li>
     * <li>Replacing occurrcences of "%w" with the video image width.</li>
     * <li>Replacing occurrcences of "%h" with the video image height.</li>
     * <li>Prepending the path of the transcoder directory if the transcoder is found there.</li>
     * </ul>
     *
     * @param command                  The command line string.
     * @param maxBitRate               The maximum bitrate to use. May not be {@code null}.
     * @param videoTranscodingSettings Parameters used when transcoding video. May be {@code null}.
     * @param mediaFile                The media file.
     * @param in                       Data to feed to the process.  May be {@code null}.  @return The newly created input stream.
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops") // TODO #585
    private TranscodeInputStream createTranscodeInputStream(String command, Integer maxBitRate,
                                                            VideoTranscodingSettings videoTranscodingSettings, MediaFile mediaFile, InputStream in) throws IOException {

        String title = mediaFile.getTitle();
        String album = mediaFile.getAlbumName();
        String artist = mediaFile.getArtist();

        if (title == null) {
            title = "Unknown Song";
        }
        if (album == null) {
            album = "Unknown Album";
        }
        if (artist == null) {
            artist = "Unknown Artist";
        }

        List<String> result = new LinkedList<>(Arrays.asList(StringUtil.split(command)));
        result.set(0, getTranscodeDirectory().getPath() + File.separatorChar + result.get(0));

        File tmpFile = null;

        for (int i = 1; i < result.size(); i++) {
            String cmd = result.get(i);
            if (cmd.contains("%b")) {
                cmd = cmd.replace("%b", String.valueOf(maxBitRate));
            }
            if (cmd.contains("%t")) {
                cmd = cmd.replace("%t", title);
            }
            if (cmd.contains("%l")) {
                cmd = cmd.replace("%l", album);
            }
            if (cmd.contains("%a")) {
                cmd = cmd.replace("%a", artist);
            }
            if (cmd.contains("%o") && videoTranscodingSettings != null) {
                cmd = cmd.replace("%o", String.valueOf(videoTranscodingSettings.getTimeOffset()));
            }
            if (cmd.contains("%d") && videoTranscodingSettings != null) {
                cmd = cmd.replace("%d", String.valueOf(videoTranscodingSettings.getDuration()));
            }
            if (cmd.contains("%w") && videoTranscodingSettings != null) {
                cmd = cmd.replace("%w", String.valueOf(videoTranscodingSettings.getWidth()));
            }
            if (cmd.contains("%h") && videoTranscodingSettings != null) {
                cmd = cmd.replace("%h", String.valueOf(videoTranscodingSettings.getHeight()));
            }
            if (cmd.contains("%s")) {

                // Work-around for filename character encoding problem on Windows.
                // Create temporary file, and feed this to the transcoder.
                String path = mediaFile.getFile().getAbsolutePath();
                if (PlayerUtils.isWindows() && !mediaFile.isVideo() && !StringUtils.isAsciiPrintable(path)) {
                    tmpFile = File.createTempFile("airsonic", "." + FilenameUtils.getExtension(path));
                    tmpFile.deleteOnExit();
                    FileUtils.copyFile(new File(path), tmpFile);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Created tmp file: " + tmpFile);
                    }
                    cmd = cmd.replace("%s", tmpFile.getPath());
                } else {
                    cmd = cmd.replace("%s", path);
                }
            }

            result.set(i, cmd);
        }
        return new TranscodeInputStream(new ProcessBuilder(result), in, tmpFile);
    }

    /**
     * Returns an applicable transcoding for the given file and player, or <code>null</code> if no
     * transcoding should be done.
     */
    private Transcoding getTranscoding(MediaFile mediaFile, Player player, String preferredTargetFormat, boolean hls) {

        if (hls) {
            return new Transcoding(null, "hls", mediaFile.getFormat(), "ts", settingsService.getHlsCommand(), null, null, true);
        }

        if (FORMAT_RAW.equals(preferredTargetFormat)) {
            return null;
        }

        List<Transcoding> applicableTranscodings = new LinkedList<>();
        String suffix = mediaFile.getFormat();

        // This is what I'd like todo, but this will most likely break video transcoding as video transcoding is
        // never expected to be null
//        if(StringUtils.equalsIgnoreCase(preferredTargetFormat, suffix)) {
//            LOG.debug("Target formats are the same, returning no transcoding");
//            return null;
//        }

        List<Transcoding> transcodingsForPlayer = getTranscodingsForPlayer(player);
        for (Transcoding transcoding : transcodingsForPlayer) {
            // special case for now as video must have a transcoding
            if (mediaFile.isVideo() && StringUtils.equalsIgnoreCase(preferredTargetFormat, transcoding.getTargetFormat())) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Detected source to target format match for video");
                }
                return transcoding;
            }
            for (String sourceFormat : transcoding.getSourceFormatsAsArray()) {
                if (sourceFormat.equalsIgnoreCase(suffix)) {
                    if (isTranscodingInstalled(transcoding)) {
                        applicableTranscodings.add(transcoding);
                    }
                }
            }
        }

        if (applicableTranscodings.isEmpty()) {
            return null;
        }

        for (Transcoding transcoding : applicableTranscodings) {
            if (transcoding.getTargetFormat().equalsIgnoreCase(preferredTargetFormat)) {
                return transcoding;
            }
        }

        return applicableTranscodings.get(0);
    }

    /**
     * Returns whether transcoding is supported (i.e. whether ffmpeg is installed or not).
     *
     * @param mediaFile If not null, returns whether transcoding is supported for this file.
     * @return Whether transcoding is supported.
     */
    public boolean isTranscodingSupported(MediaFile mediaFile) {
        List<Transcoding> transcodings = getAllTranscodings();
        for (Transcoding transcoding : transcodings) {
            if (! isTranscodingInstalled(transcoding)) {
                continue;
            }
            if (mediaFile == null) {
                return true;
            }
            for (String sourceFormat : transcoding.getSourceFormatsAsArray()) {
                if (sourceFormat.equalsIgnoreCase(mediaFile.getFormat())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isTranscodingInstalled(Transcoding transcoding) {
        return isTranscodingStepInstalled(transcoding.getStep1()) &&
                isTranscodingStepInstalled(transcoding.getStep2()) &&
                isTranscodingStepInstalled(transcoding.getStep3());
    }

    private boolean isTranscodingStepInstalled(String step) {
        if (StringUtils.isEmpty(step)) {
            return true;
        }
        String executable = StringUtil.split(step)[0];
        PrefixFileFilter filter = new PrefixFileFilter(executable);
        String[] matches = getTranscodeDirectory().list(filter);
        return matches != null && matches.length > 0;
    }

    /**
     * Returns the length (or predicted/expected length) of a (possibly padded) media stream
     */
    private Long getExpectedLength(Parameters parameters) {

        MediaFile file = parameters.getMediaFile();
        if (!parameters.isTranscode()) {
            return file.getFileSize();
        }

        Integer duration = file.getDurationSeconds();
        if (duration == null) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Unknown duration for " + file + ". Unable to estimate transcoded size.");
            }
            return null;
        }

        Integer maxBitRate = parameters.getMaxBitRate();
        if (maxBitRate == null) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Unknown bit rate for " + file + ". Unable to estimate transcoded size.");
            }
            return null;
        }

        // Over-estimate size a bit (2 seconds) so don't cut off early in case of small calculation differences
        return (duration + 2) * (long)maxBitRate * 1000L / 8L;
    }

    private boolean isRangeAllowed(Parameters parameters) {
        Transcoding transcoding = parameters.getTranscoding();
        List<String> steps;
        if (transcoding != null) {
            steps = Arrays.asList(transcoding.getStep3(), transcoding.getStep2(), transcoding.getStep1());
        } else {
            return true;  // not transcoding
        }

        // Verify that were able to predict the length
        if (parameters.getExpectedLength() == null) {
            return false;
        }

        // Check if last configured step uses the bitrate, if so, range should be pretty safe
        for (String step : steps) {
            if (step != null) {
                return step.contains("%b");
            }
        }
        return false;
    }

    /**
     * Returns the directory in which all transcoders are installed.
     */
    public File getTranscodeDirectory() {
        File dir = new File(SettingsService.getJpsonicHome(), "transcode");
        if (!dir.exists()) {
            boolean ok = dir.mkdir();
            if (ok) {
                if (LOG.isInfoEnabled()) {
                    LOG.info("Created directory " + dir);
                }
            } else {
                if (LOG.isWarnEnabled()) {
                    LOG.warn("Failed to create directory " + dir);
                }
            }
        }
        return dir;
    }

    public void setTranscodingDao(TranscodingDao transcodingDao) {
        this.transcodingDao = transcodingDao;
    }

    public void setSettingsService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public void setPlayerService(PlayerService playerService) {
        this.playerService = playerService;
    }

    public static class Parameters {
        private Long expectedLength;
        private boolean rangeAllowed;
        private final MediaFile mediaFile;
        private final VideoTranscodingSettings videoTranscodingSettings;
        private Integer maxBitRate;
        private Transcoding transcoding;

        public Parameters(MediaFile mediaFile, VideoTranscodingSettings videoTranscodingSettings) {
            this.mediaFile = mediaFile;
            this.videoTranscodingSettings = videoTranscodingSettings;
        }

        public void setMaxBitRate(Integer maxBitRate) {
            this.maxBitRate = maxBitRate;
        }

        public boolean isTranscode() {
            return transcoding != null;
        }

        public boolean isRangeAllowed() {
            return this.rangeAllowed;
        }

        public void setRangeAllowed(boolean rangeAllowed) {
            this.rangeAllowed = rangeAllowed;
        }

        public Long getExpectedLength() {
            return this.expectedLength;
        }

        public void setExpectedLength(Long expectedLength) {
            this.expectedLength = expectedLength;
        }

        public void setTranscoding(Transcoding transcoding) {
            this.transcoding = transcoding;
        }

        public Transcoding getTranscoding() {
            return transcoding;
        }

        public MediaFile getMediaFile() {
            return mediaFile;
        }

        public Integer getMaxBitRate() {
            return maxBitRate;
        }

        public VideoTranscodingSettings getVideoTranscodingSettings() {
            return videoTranscodingSettings;
        }
    }
}
