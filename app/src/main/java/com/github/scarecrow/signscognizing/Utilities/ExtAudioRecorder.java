package com.github.scarecrow.signscognizing.Utilities;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;

import static android.content.ContentValues.TAG;

/**
 * Created by Scarecrow on 2018/2/19.
 * 一个扩展的音频录制类 封装的自带的两个类 支持pcm和amr格式的录音
 * 使用方法 ：先构造一个AudioRecorderConfiguration 设置其录制方式及格式
 * 调用初始化将其传入 同时内部会构造两个原生类 获取资源
 *  prepare() 构造文件 及文件头
 *  start()  开始录音
 *  stop() 停止录音并删除文件 complete()停止录音并保存文件
 *  reset() 恢复至prepare()前的状态 准备下一次调用
 */

public class ExtAudioRecorder {

    private AudioRecorderConfiguration configuration;


    public ExtAudioRecorder(AudioRecorderConfiguration configuration) {
        this.configuration = configuration;

        if (configuration.isUncompressed()) {
            init(configuration.isUncompressed(),
                    configuration.getSource(),
                    configuration.getRate(),
                    configuration.getChannelConfig(),
                    configuration.getFormat());
        } else {
            int i = 0;
            do {
                init(configuration.isUncompressed(),
                        configuration.getSource(),
                        AudioRecorderConfiguration.SAMPLE_RATES[i],
                        configuration.getChannelConfig(),
                        configuration.getFormat());

            }
            while ((++i < AudioRecorderConfiguration.SAMPLE_RATES.length) & !(getState() == ExtAudioRecorder.State.INITIALIZING));
        }
    }

    /**
     * 录音的状态
     */
    public enum State {
        /**
         * 录音初始化
         */
        INITIALIZING,
        /**
         * 已准备好录音
         */
        READY,
        /**
         * 录音中
         */
        RECORDING,
        /**
         * 录音生了错误
         */
        ERROR,
        /**
         * 停止录音
         */
        STOPPED
    }

    // 不压缩将使用这个进行录音
    private AudioRecord audioRecorder = null;

    // 压缩将使用这进行录音
    private MediaRecorder mediaRecorder = null;

    // 当前的振幅 (只有在未压缩的模式下)
    private int cAmplitude = 0;

    // 录音状态
    private State state;

    // 文件 (只有在未压缩的模式下)
    private RandomAccessFile randomAccessWriter;

    private int bufferSize;

    // 录音 通知周期(只有在未压缩的模式下)
    private int framePeriod;
    // 输出的字节(只有在未压缩的模式下)
    private byte[] buffer;

    private short samples;
    private short channels;

    // 写入头文件的字节数(只有在未压缩的模式下)
    // after stop() is called, this size is written to the header/data chunk in
    // the wave file
    private int payloadSize;
    //录音的开始时间
    private long startTime;

    private String filePath;

    private static String fileFolder = Environment.getExternalStorageDirectory()
            .getPath() + "/sign_recognize_voice_cache/";

    /**
     * 返回录音的状态
     *
     * @return 录音的状态
     */
    public State getState() {
        return state;
    }

    /*
     *
     * Method used for recording.
     */
    private AudioRecord.OnRecordPositionUpdateListener updateListener
            = new AudioRecord.OnRecordPositionUpdateListener() {
        @Override
        public void onPeriodicNotification(AudioRecord recorder) {
            audioRecorder.read(buffer, 0, buffer.length); // Fill buffer
            try {
                if (state == State.RECORDING) {
                    randomAccessWriter.write(buffer); // Write buffer to file
                    payloadSize += buffer.length;
                    if (samples == 16) {
                        for (int i = 0; i < buffer.length / 2; i++) { // 16bit sample size
                            short curSample = getShort(buffer[i * 2], buffer[i * 2 + 1]);
                            if (curSample > cAmplitude) { // Check amplitude
                                cAmplitude = curSample;
                            }
                        }
                    } else { // 8bit sample size
                        for (int i = 0; i < buffer.length; i++) {
                            if (buffer[i] > cAmplitude) { // Check amplitude
                                cAmplitude = buffer[i];
                            }
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                Log.e(ExtAudioRecorder.class.getName(), "Error occured in updateListener, recording is aborted");
                //stop();
            }
        }

        @Override
        public void onMarkerReached(AudioRecord recorder) {
            // NOT USED
        }
    };

    /**
     * 默认的构造方法，如果压缩录音，剩下的参数可以为0.这个方法不会抛出异常，但是会设置状态为 {@link State#ERROR}
     *
     * @param uncompressed  是否压缩录音 true不压缩，false压缩
     * @param audioSource   音频源：指的是从哪里采集音频。通过 {@link AudioRecord} 的一些常量去设置
     * @param sampleRate    采样率：音频的采样频率，每秒钟能够采样的次数，采样率越高，音质越高。
     *                      给出的实例是44100、22050、11025但不限于这几个参数。
     *                      例如要采集低质量的音频就可以使用4000、8000等低采样率。
     * @param channelConfig 声道设置：Android支持双声道立体声和单声道。MONO单声道，STEREO立体声
     * @param audioFormat   编码制式和采样大小：采集来的数据当然使用PCM编码(脉冲代码调制编码，即PCM编码。
     *                      PCM通过抽样、量化、编码三个步骤将连续变化的模拟信号转换为数字编码。)
     *                      android支持的采样大小16bit 或者8bit。当然采样大小越大，那么信息量越多，
     *                      ，现在主流的采样大小都是16bit，在低质量的语音传输的时候8bit足够了。
     */
    private void init(boolean uncompressed, int audioSource, int sampleRate, int channelConfig, int audioFormat) {
        try {
            if (uncompressed) { // RECORDING_UNCOMPRESSED
                if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
                    samples = 16;
                } else {
                    samples = 8;
                }

                if (channelConfig == AudioFormat.CHANNEL_CONFIGURATION_MONO) {
                    channels = 1;
                } else {
                    channels = 2;
                }

                framePeriod = sampleRate * configuration.getTimerInterval() / 1000;
                bufferSize = framePeriod * 2 * samples * channels / 8;
                int min_buffer_size = AudioRecord
                        .getMinBufferSize(sampleRate, channelConfig, audioFormat);
                if (bufferSize < min_buffer_size) {
                    // Check to make sure buffer size is not smaller than the smallest allowed one
                    bufferSize = min_buffer_size;
                    // Set frame period and timer interval accordingly
                    framePeriod = bufferSize / (2 * samples * channels / 8);
                    Log.w(ExtAudioRecorder.class.getName(),
                            "Increasing buffer size to " + Integer.toString(bufferSize));
                }

                audioRecorder = new AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize);

                if (audioRecorder.getState() != AudioRecord.STATE_INITIALIZED)
                    throw new Exception("AudioRecord initialization failed");
                audioRecorder.setRecordPositionUpdateListener(updateListener);
                audioRecorder.setPositionNotificationPeriod(framePeriod);
            } else { // RECORDING_COMPRESSED
                mediaRecorder = new MediaRecorder();
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            }
            cAmplitude = 0;
            filePath = null;
            state = State.INITIALIZING;
        } catch (Exception e) {
            if (e.getMessage() != null) {
                Log.e(ExtAudioRecorder.class.getName(), e.getMessage());
            } else {
                Log.e(ExtAudioRecorder.class.getName(), "Unknown error occured while initializing recording");
            }
            state = State.ERROR;
        }
    }


    /**
     * 设置输出的文件路径
     */
    private void acquireOutputFile() {
        try {
            if (state == State.INITIALIZING) {

                filePath = fileFolder + getCurrentDate() + ".pcm";
                //这里默认为pcm文件了
                if (!configuration.isUncompressed()) {
                    mediaRecorder.setOutputFile(filePath);
                }
            }
        } catch (Exception e) {
            if (e.getMessage() != null) {
                Log.e(ExtAudioRecorder.class.getName(), e.getMessage());
            } else {
                Log.e(ExtAudioRecorder.class.getName(),
                        "Unknown error occured while setting output path");
            }
            state = State.ERROR;
        }
    }

    private String getCurrentDate() {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy_MM_dd_HHmmss");
        Date curDate = new Date(System.currentTimeMillis());// 获取当前时间
        return formatter.format(curDate);
    }


    /**
     * Returns the largest amplitude sampled since the last call to this method.
     *
     * @return returns the largest amplitude since the last call, or 0 when not
     * in recording state.
     */
    private int getMaxAmplitude() {
        if (state == State.RECORDING) {
            if (configuration.isUncompressed()) {
                int result = cAmplitude;
                cAmplitude = 0;
                return result;
            } else {
                try {
                    return mediaRecorder.getMaxAmplitude();
                } catch (IllegalStateException e) {
                    return 0;
                }
            }
        } else {
            return 0;
        }
    }


    /**
     * 准备录音的录音机, 如果 state 不是 {@link State#INITIALIZING} 或文件路径为null
     * 将设置 state 为 {@link State#ERROR}。如果发生异常不会抛出，而是设置 state 为
     * {@link State#ERROR}
     */
    public void prepare() {
        try {
            if (state == State.INITIALIZING || state == State.ERROR) {
                acquireOutputFile();
                if (configuration.isUncompressed()) {
                    if ((audioRecorder.getState() == AudioRecord.STATE_INITIALIZED) & (filePath != null)) {
                        // 写文件头
                        createVoiceFileHeader();
                        buffer = new byte[framePeriod * samples / 8 * channels];
                        state = State.READY;
                    } else {
                        Log.e(ExtAudioRecorder.class.getName(),
                                "prepare() method called on uninitialized recorder");
                        state = State.ERROR;
                    }
                } else {
                    mediaRecorder.prepare();
                    state = State.READY;
                }
            } else {
                Log.e(ExtAudioRecorder.class.getName(), "prepare() method called on illegal state");
                release();
                state = State.ERROR;
            }
        } catch (Exception e) {
            if (e.getMessage() != null) {
                Log.e(ExtAudioRecorder.class.getName(), e.getMessage());
            } else {
                Log.e(ExtAudioRecorder.class.getName(), "Unknown error occured in prepare()");
            }
            state = State.ERROR;
        }
    }

    private void createVoiceFileHeader() throws IOException {
        randomAccessWriter = new RandomAccessFile(filePath, "rw");
        //设置文件长度为0，为了防止这个file以存在
        randomAccessWriter.setLength(0);
        randomAccessWriter.writeBytes("RIFF");
        //不知道文件最后的大小，所以设置0
        randomAccessWriter.writeInt(0);
        randomAccessWriter.writeBytes("WAVE");
        randomAccessWriter.writeBytes("fmt ");
        // Sub-chunk
        // size,
        // 16
        // for
        // PCM
        randomAccessWriter.writeInt(Integer.reverseBytes(16));
        // AudioFormat, 1 为 PCM
        randomAccessWriter.writeShort(Short.reverseBytes((short) 1));
        // 数字为声道, 1 为 mono, 2 为 stereo
        randomAccessWriter.writeShort(Short.reverseBytes(channels));
        // 采样率
        randomAccessWriter.writeInt(Integer.reverseBytes(configuration.getRate()));
        // 采样率, SampleRate*NumberOfChannels*BitsPerSample/8
        randomAccessWriter.writeInt(Integer.reverseBytes(configuration.getRate() * samples * channels / 8));
        randomAccessWriter.writeShort(Short.reverseBytes((short) (channels * samples / 8)));
        // Block
        // align,
        // NumberOfChannels*BitsPerSample/8
        randomAccessWriter.writeShort(Short.reverseBytes(samples)); // Bits per sample
        randomAccessWriter.writeBytes("data");
        randomAccessWriter.writeInt(0);
        // Data chunk size don't known yet, write 0

    }

    /**
     * 释放与这个类相关的资源，和移除不必要的文件，在必要的时候
     */
    public void release() {
        if (state == State.RECORDING) {
            stop();
        } else {
            if ((state == State.READY) & (configuration.isUncompressed())) {
                try {
                    randomAccessWriter.close(); // 删除准备文件
                } catch (IOException e) {
                    Log.e(ExtAudioRecorder.class.getName(), "I/O exception occured while closing output file");
                }
                (new File(filePath)).delete();
            }
        }

        if (audioRecorder != null)
            audioRecorder.release();

    }

    /**
     * 停止录音 会删除录下的文件
     */
    public void stop() {
        complete();

        File file = new File(filePath);
        if (file.exists() && !file.isDirectory()) {
            file.delete();
            filePath = null;
        }
    }

    /**
     * 重置录音，并设置 state 为 {@link State#INITIALIZING}，
     * 如果当前状态为 {@link State#RECORDING}，将会停止录音。
     * 这个方法不会抛出异常，但是会设置状态为 {@link State#ERROR}
     */
    public void reset() {
        Log.d("ExtAudioRecorder", "on reset called");
        try {
            filePath = null; // Reset file path
            cAmplitude = 0; // Reset amplitude
            if (configuration.isUncompressed()) {
                audioRecorder = new AudioRecord(configuration.getSource(), configuration.getRate(),
                        channels + 1, configuration.getFormat(), bufferSize);
                audioRecorder.setRecordPositionUpdateListener(updateListener);
                audioRecorder.setPositionNotificationPeriod(framePeriod);
            } else {
                mediaRecorder = new MediaRecorder();
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mediaRecorder
                        .setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                mediaRecorder
                        .setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            }
            state = State.INITIALIZING;
        } catch (Exception e) {
            Log.e(ExtAudioRecorder.class.getName(), e.getMessage());
            state = State.ERROR;
        }
    }

    /**
     * 开始录音，并设置 state 为 {@link State#RECORDING}。
     * 在调用这个方法前必须调用 {@link ExtAudioRecorder#prepare()} 方法
     */
    public void start() {
        if (state == State.READY) {
            if (configuration.isUncompressed()) {
                payloadSize = 0;
                audioRecorder.startRecording();
                audioRecorder.read(buffer, 0, buffer.length);
            } else {
                mediaRecorder.start();
            }
            state = State.RECORDING;
            this.startTime = (new Date()).getTime();
            startGetMaxAmplitudeThread();
        } else {
            Log.e(ExtAudioRecorder.class.getName(), "start() called on illegal state");
            state = State.ERROR;
        }
    }

    /**
     * 完成录音，并设置 state 为 {@link State#STOPPED}。
     * 如果要继续使用，则需要调用 {@link #reset()} 方法
     * 这个方法会保存录下的音频文件 并返回音频文件的URI
     * @return 录音的时间
     */
    public String complete() {
        if (state == State.RECORDING) {
            if (configuration.isUncompressed()) {
                audioRecorder.stop();

                try {
                    randomAccessWriter.seek(4); // Write size to RIFF header
                    randomAccessWriter.writeInt(Integer.reverseBytes(36 + payloadSize));

                    randomAccessWriter.seek(40); // Write size to Subchunk2Size
                    // field
                    randomAccessWriter.writeInt(Integer.reverseBytes(payloadSize));

                    randomAccessWriter.close();
                    Log.d(TAG, "on complete: voice file writing task");
                } catch (IOException e) {
                    Log.e(ExtAudioRecorder.class.getName(),
                            "I/O exception occured while closing output file");
                    state = State.ERROR;
                }
            } else {
                try {
                    mediaRecorder.stop();
                } catch (Exception e) {
                }
            }
            state = State.STOPPED;

            File file = new File(filePath);
            if (file.exists() && file.isFile()) {
                if (file.length() == 0L) {
                    file.delete();
                    return "文件长度为0";
                } else {
                    int time = (int) ((new Date()).getTime() - this.startTime) / 1000;
                    return filePath;
                }
            } else {
                return "文件不存在";
            }
        } else {
            Log.e(ExtAudioRecorder.class.getName(), "stop() called on illegal state");
            state = State.ERROR;
            return "recorder状态错误";
        }
    }

    private void startGetMaxAmplitudeThread() {
        if (configuration.getHandler() != null) {
            new Thread(new Runnable() {
                public void run() {
                    while (true) {
                        if (state == State.RECORDING) {
                            double curr_amplitude = getMaxAmplitude() / 20;
                            Log.d(TAG, "run: curr_amplitude: " + curr_amplitude);
                            configuration.getHandler().obtainMessage(0, curr_amplitude)
                                    .sendToTarget();
                            SystemClock.sleep(100);
                            continue;
                        }
                        return;
                    }
                }
            }).start();
        }
    }

    /**
     * Converts a byte[2] to a short, in LITTLE_ENDIAN format
     */
    private short getShort(byte argB1, byte argB2) {
        return (short) (argB1 | (argB2 << 8));
    }


}
