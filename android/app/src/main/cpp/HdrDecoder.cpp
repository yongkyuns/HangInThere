#include "HdrDecoder.h"
#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <limits>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavformat/avformat.h>
#include <libavutil/pixdesc.h>
}

namespace hanginthere {
namespace {
void require(bool valid, const char* message) { if (!valid) throw std::runtime_error(message); }
void checked(int result, const char* stage) {
    // Error codes identify the failing stage without embedding private metadata.
    if (result < 0) throw std::runtime_error(std::string(stage) + " (" + std::to_string(result) + ")");
}
}

struct HdrDecoder::State {
    int fd = -1, track = -1, expectedTransfer = 0;
    int64_t offset = 0, length = 0, position = 0, ptsUs = -1;
    AVFormatContext* format = nullptr;
    AVIOContext* io = nullptr;
    AVCodecContext* codec = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* decoded = nullptr;
    AVFrame* output = nullptr;
    AVFilterGraph* graph = nullptr;
    AVFilterContext* source = nullptr;
    AVFilterContext* sink = nullptr;
    bool flushed = false, ended = false;
    const std::function<bool()>* cancellation = nullptr;
    int width = 0, height = 0, inputFormat = -1, inputRange = 0;
    bool cancelled() const { return cancellation && *cancellation && (*cancellation)(); }
    struct CancellationScope {
        State& owner;
        CancellationScope(State& owner, const std::function<bool()>& callback) : owner(owner) { owner.cancellation = &callback; }
        ~CancellationScope() { owner.cancellation = nullptr; }
    };

    ~State() {
        avfilter_graph_free(&graph);
        av_frame_free(&output); av_frame_free(&decoded);
        av_packet_free(&packet); avcodec_free_context(&codec);
        avformat_close_input(&format);
        if (io) { av_freep(&io->buffer); avio_context_free(&io); }
        if (fd >= 0) ::close(fd);
    }
    static int read(void* opaque, uint8_t* buffer, int count) {
        auto& s = *static_cast<State*>(opaque);
        if (s.cancelled()) return AVERROR_EXIT;
        if (s.position >= s.length) return AVERROR_EOF;
        count = static_cast<int>(std::min<int64_t>(count, s.length - s.position));
        ssize_t result;
        do { result = pread(s.fd, buffer, count, s.offset + s.position); } while (result < 0 && errno == EINTR);
        if (result < 0) return AVERROR(errno);
        if (!result) return AVERROR_EOF;
        s.position += result;
        return static_cast<int>(result);
    }
    static int interrupt(void* opaque) { return static_cast<State*>(opaque)->cancelled(); }
    static int64_t seek(void* opaque, int64_t target, int whence) {
        auto& s = *static_cast<State*>(opaque);
        if (whence == AVSEEK_SIZE) return s.length;
        whence &= ~AVSEEK_FORCE;
        const int64_t base = whence == SEEK_SET ? 0 : whence == SEEK_CUR ? s.position : whence == SEEK_END ? s.length : -1;
        if (base < 0 || target < -base || target > s.length - base) return AVERROR(EINVAL);
        s.position = base + target;
        return s.position;
    }
    void configureFilter() {
        width = decoded->width; height = decoded->height; inputFormat = decoded->format; inputRange = decoded->color_range;
        graph = avfilter_graph_alloc(); require(graph, "HDR filter allocation failed");
        graph->nb_threads = 1; // Bounded replay work, independent of host CPU count.
        const auto timeBase = format->streams[track]->time_base;
        char args[256];
        std::snprintf(args, sizeof(args), "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d:pixel_aspect=1/1:colorspace=%d:range=%d",
                      width, height, inputFormat, timeBase.num, timeBase.den, decoded->colorspace, decoded->color_range);
        checked(avfilter_graph_create_filter(&source, avfilter_get_by_name("buffer"), "input", args, nullptr, graph), "HDR input filter");
        checked(avfilter_graph_create_filter(&sink, avfilter_get_by_name("buffersink"), "output", nullptr, nullptr, graph), "HDR output filter");
        // Same declared Hable/1000-nit mapping as private evaluation preparation.
        // Resize before floating-point tone mapping to bound memory at 1280 pixels.
        const double factor = std::min(1.0, 1280.0 / std::max(width, height));
        const int targetWidth = static_cast<int>(width * factor / 2) * 2;
        const int targetHeight = static_cast<int>(height * factor / 2) * 2;
        require(targetWidth >= 16 && targetHeight >= 16, "HDR image is too small");
        const std::string filters = "scale=" + std::to_string(targetWidth) + ":" + std::to_string(targetHeight) +
            ":flags=lanczos,setsar=1,zscale=transfer=linear:npl=100,format=gbrpf32le,"
            "tonemap=tonemap=hable:desat=0:peak=10,"
            "zscale=primaries=bt709:transfer=bt709:matrix=bt709:range=limited,format=yuv420p,"
            "setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=limited";
        AVFilterInOut* in = avfilter_inout_alloc();
        AVFilterInOut* out = avfilter_inout_alloc();
        if (!in || !out) { avfilter_inout_free(&in); avfilter_inout_free(&out); throw std::runtime_error("HDR filter link allocation failed"); }
        in->name = av_strdup("out"); in->filter_ctx = sink; in->pad_idx = 0;
        out->name = av_strdup("in"); out->filter_ctx = source; out->pad_idx = 0;
        int result = avfilter_graph_parse_ptr(graph, filters.c_str(), &in, &out, nullptr);
        avfilter_inout_free(&in); avfilter_inout_free(&out);
        checked(result, "HDR filter parse");
        checked(avfilter_graph_config(graph, nullptr), "HDR filter configuration");
    }
    void verifyInput() {
        const auto* descriptor = av_pix_fmt_desc_get(static_cast<AVPixelFormat>(decoded->format));
        require(descriptor && descriptor->nb_components == 3 && descriptor->comp[0].depth == 10 &&
                descriptor->log2_chroma_w == 1 && descriptor->log2_chroma_h == 1, "Unsupported HDR pixel layout");
        require(decoded->width >= 16 && decoded->height >= 16 && decoded->width <= 4096 && decoded->height <= 4096 &&
                static_cast<int64_t>(decoded->width) * decoded->height <= 4096 * 2160,
                "Unsupported HDR dimensions");
        require(decoded->color_primaries == AVCOL_PRI_BT2020 && decoded->colorspace == AVCOL_SPC_BT2020_NCL &&
                decoded->color_trc == expectedTransfer &&
                (decoded->color_range == AVCOL_RANGE_MPEG || decoded->color_range == AVCOL_RANGE_JPEG), "Unverified HDR color metadata");
        require(!graph || (width == decoded->width && height == decoded->height && inputFormat == decoded->format && inputRange == decoded->color_range),
                "HDR format changed during replay");
    }
};

HdrDecoder::HdrDecoder(int descriptor, int64_t offset, int64_t length, int expectedTransfer, const std::function<bool()>& cancelled) : state(new State) {
    auto& s = *state;
    State::CancellationScope scope(s, cancelled);
    if (s.cancelled()) throw HdrCancelled();
    require(expectedTransfer == AVCOL_TRC_ARIB_STD_B67 || expectedTransfer == AVCOL_TRC_SMPTE2084, "Unsupported HDR transfer");
    s.expectedTransfer = expectedTransfer;
    s.fd = dup(descriptor); require(s.fd >= 0, "Cannot duplicate video descriptor");
    struct stat status {};
    require(fstat(s.fd, &status) == 0 && S_ISREG(status.st_mode), "HDR import needs a seekable file descriptor");
    require(offset >= 0 && offset <= status.st_size, "Invalid video descriptor offset");
    s.offset = offset; s.length = length < 0 ? status.st_size - offset : length;
    require(s.length > 0 && s.length <= status.st_size - offset, "Invalid video descriptor length");
    // FFmpeg's default logger can include source metadata; imports retain only stage/error codes.
    av_log_set_level(AV_LOG_QUIET);
    auto* buffer = static_cast<uint8_t*>(av_malloc(65536)); require(buffer, "HDR I/O allocation failed");
    s.io = avio_alloc_context(buffer, 65536, 0, &s, State::read, nullptr, State::seek);
    if (!s.io) { av_free(buffer); throw std::runtime_error("HDR I/O context allocation failed"); }
    s.format = avformat_alloc_context(); require(s.format, "HDR format allocation failed");
    s.format->pb = s.io; s.format->flags |= AVFMT_FLAG_CUSTOM_IO;
    s.format->interrupt_callback = AVIOInterruptCB{State::interrupt, &s};
    s.format->max_streams = 16;
    // Only MOV/MP4 and the HEVC decoder are built; no protocols or external URLs.
    const int opened = avformat_open_input(&s.format, nullptr, av_find_input_format("mov"), nullptr);
    if (s.cancelled()) throw HdrCancelled();
    checked(opened, "HDR container open");
    // MOV headers carry codec parameters and CSD; do not open an unbounded probe decoder.
    for (unsigned i = 0; i < s.format->nb_streams; ++i) {
        if (s.format->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) { s.track = static_cast<int>(i); break; }
    }
    require(s.track >= 0, "No HDR video track");
    const auto* parameters = s.format->streams[s.track]->codecpar;
    require(parameters->codec_id == AV_CODEC_ID_HEVC, "Unsupported HDR codec");
    require(parameters->width >= 16 && parameters->height >= 16 && parameters->width <= 4096 && parameters->height <= 4096 &&
            static_cast<int64_t>(parameters->width) * parameters->height <= 4096 * 2160, "Unsupported HDR dimensions");
    s.codec = avcodec_alloc_context3(avcodec_find_decoder(AV_CODEC_ID_HEVC)); require(s.codec, "HDR decoder allocation failed");
    checked(avcodec_parameters_to_context(s.codec, parameters), "HDR decoder parameters");
    s.codec->thread_count = 2; s.codec->thread_type = FF_THREAD_SLICE;
    s.codec->max_pixels = 4096 * 2160;
    s.codec->err_recognition = AV_EF_EXPLODE;
    checked(avcodec_open2(s.codec, avcodec_find_decoder(AV_CODEC_ID_HEVC), nullptr), "HDR decoder open");
    s.packet = av_packet_alloc(); s.decoded = av_frame_alloc(); s.output = av_frame_alloc();
    require(s.packet && s.decoded && s.output, "HDR frame allocation failed");
}
HdrDecoder::~HdrDecoder() = default;

const AVFrame* HdrDecoder::next(const std::function<bool()>& cancelled) {
    auto& s = *state;
    State::CancellationScope scope(s, cancelled);
    if (s.cancelled()) return nullptr;
    if (s.ended) return nullptr;
    av_frame_unref(s.output);
    while (true) {
        if (s.cancelled()) return nullptr;
        int result = avcodec_receive_frame(s.codec, s.decoded);
        if (result == AVERROR_EOF) { s.ended = true; return nullptr; }
        if (result == AVERROR(EAGAIN)) {
            require(!s.flushed, "HDR decoder failed to drain");
            do {
                av_packet_unref(s.packet);
                result = av_read_frame(s.format, s.packet);
                if (s.cancelled()) return nullptr;
                if (result == AVERROR_EOF) break;
                checked(result, "HDR packet read");
            } while (s.packet->stream_index != s.track);
            if (result == AVERROR_EOF) { checked(avcodec_send_packet(s.codec, nullptr), "HDR decoder flush"); s.flushed = true; }
            else checked(avcodec_send_packet(s.codec, s.packet), "HDR packet decode");
            continue;
        }
        checked(result, "HDR frame decode");
        s.verifyInput();
        const int64_t pts = s.decoded->best_effort_timestamp;
        require(pts != AV_NOPTS_VALUE, "Missing HDR source PTS");
        const auto rounding = static_cast<AVRounding>(AV_ROUND_DOWN | AV_ROUND_PASS_MINMAX);
        const int64_t ptsUs = av_rescale_q_rnd(pts, s.format->streams[s.track]->time_base, AVRational{1, 1000000}, rounding);
        require(ptsUs >= 0 && ptsUs > s.ptsUs, "Invalid or repeated HDR source PTS");
        s.decoded->pts = pts;
        if (!s.graph) s.configureFilter();
        checked(av_buffersrc_add_frame_flags(s.source, s.decoded, AV_BUFFERSRC_FLAG_KEEP_REF), "HDR tone mapping input");
        checked(av_buffersink_get_frame(s.sink, s.output), "HDR tone mapping output");
        require(s.output->format == AV_PIX_FMT_YUV420P && s.output->color_primaries == AVCOL_PRI_BT709 &&
                s.output->color_trc == AVCOL_TRC_BT709 && s.output->colorspace == AVCOL_SPC_BT709 &&
                s.output->color_range == AVCOL_RANGE_MPEG, "HDR filter did not produce verified SDR");
        require(av_rescale_q_rnd(s.output->pts, av_buffersink_get_time_base(s.sink), AVRational{1, 1000000}, rounding) == ptsUs,
                "HDR filter changed source PTS");
        s.ptsUs = ptsUs; av_frame_unref(s.decoded);
        return s.output;
    }
}
int64_t HdrDecoder::presentationTimeUs() const { return state->ptsUs; }
}
