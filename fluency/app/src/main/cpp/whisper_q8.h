// Loads a whisper.cpp model file (legacy ggml format) with its Q5_0 weights handed to whisper.cpp
// as Q8_0 - converted while reading, nothing is written to disk.
//
// Why: Q5_0 has no fast matrix kernels on ARM (no i8mm/KleidiAI/repacked path) and the NPU backend
// does not support it; Q8_0 has all of them. The conversion is exact: a Q5_0 block holds
// (q - 16) * d with q in 0..31, a Q8_0 block holds int8 * d - same scale d, int8 = q - 16.
//
// File layout (whisper.cpp's loader reads it front to back): magic, 11 int32 hparams (the last is
// ftype), mel filters, vocabulary, then per tensor: n_dims, name length, type, dims, name, data.
// The stream is planned once (headers only), then served in segments: unchanged bytes are copied,
// the ftype and the type of every Q5_0 tensor are replaced, Q5_0 data is converted block by block.
#pragma once

#include "ggml.h"
#include "whisper.h"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace whisper_q8 {

constexpr int QK = 32;
constexpr size_t Q5_BLOCK = 2 + 4 + QK / 2; // fp16 d, 32 high bits, 16 bytes of low nibbles
constexpr size_t Q8_BLOCK = 2 + QK;         // fp16 d, 32 int8

/** Converts [n] Q5_0 blocks to Q8_0 (ggml's dequantize_row_q5_0 without the multiplication). */
inline void convert_blocks(const uint8_t * q5, uint8_t * q8, size_t n) {
    for (size_t b = 0; b < n; ++b, q5 += Q5_BLOCK, q8 += Q8_BLOCK) {
        uint32_t qh;
        std::memcpy(&qh, q5 + 2, sizeof(qh));
        const uint8_t * qs = q5 + 6;
        q8[0] = q5[0];
        q8[1] = q5[1];
        auto * out = reinterpret_cast<int8_t *>(q8 + 2);
        for (int j = 0; j < QK / 2; ++j) {
            const uint8_t xh_0 = static_cast<uint8_t>(((qh >> j) << 4) & 0x10);
            const uint8_t xh_1 = static_cast<uint8_t>((qh >> (j + 12)) & 0x10);
            out[j] = static_cast<int8_t>(((qs[j] & 0x0F) | xh_0) - 16);
            out[j + QK / 2] = static_cast<int8_t>(((qs[j] >> 4) | xh_1) - 16);
        }
    }
}

class Stream {
  public:
    ~Stream() { close(); }

    /**
     * Opens [path] and plans the conversion. False if the file is not a whisper model with Q5_0
     * weights (it is then loaded as it is) or cannot be read.
     */
    bool open(const std::string & path) {
        f_ = std::fopen(path.c_str(), "rb");
        if (f_ == nullptr) return false;
        if (fseeko(f_, 0, SEEK_END) != 0) return false;
        size_ = static_cast<uint64_t>(ftello(f_));
        pos_ = UINT64_MAX;
        if (!plan()) {
            segs_.clear();
            return false;
        }
        return true;
    }

    int converted_tensors() const { return converted_; }
    bool failed() const { return failed_; }

    whisper_model_loader loader() {
        whisper_model_loader l{};
        l.context = this;
        l.read = [](void * ctx, void * out, size_t n) { return static_cast<Stream *>(ctx)->read(out, n); };
        l.eof = [](void * ctx) { return static_cast<Stream *>(ctx)->eof_; };
        l.close = [](void * ctx) { static_cast<Stream *>(ctx)->close(); };
        return l;
    }

    void close() {
        if (f_ != nullptr) std::fclose(f_);
        f_ = nullptr;
    }

  private:
    struct Segment {
        enum Kind { COPY, CONVERT, BYTES } kind;
        uint64_t src; // file offset of the first byte (COPY, CONVERT)
        uint64_t len; // bytes handed to whisper.cpp
        std::string bytes;
    };

    FILE * f_ = nullptr;
    uint64_t size_ = 0;
    uint64_t pos_ = UINT64_MAX; // current file position, UINT64_MAX = unknown
    std::vector<Segment> segs_;
    size_t seg_ = 0;
    uint64_t off_ = 0; // bytes of segs_[seg_] already served
    bool eof_ = false;
    bool failed_ = false;
    int converted_ = 0;
    std::vector<uint8_t> in_;

    bool read_at(uint64_t at, void * dst, size_t n) {
        if (at + n > size_) return false;
        if (pos_ != at) {
            if (fseeko(f_, static_cast<off_t>(at), SEEK_SET) != 0) {
                pos_ = UINT64_MAX;
                return false;
            }
        }
        const size_t got = std::fread(dst, 1, n, f_);
        pos_ = at + got;
        return got == n;
    }

    void bytes(const void * p, size_t n) {
        segs_.push_back({Segment::BYTES, 0, n, std::string(static_cast<const char *>(p), n)});
    }

    bool plan() {
        uint64_t at = 0;
        auto rd = [&](void * dst, size_t n) {
            const bool ok = read_at(at, dst, n);
            at += n;
            return ok;
        };
        uint32_t magic = 0;
        int32_t hp[11];
        if (!rd(&magic, sizeof(magic)) || magic != GGML_FILE_MAGIC) return false;
        if (!rd(hp, sizeof(hp))) return false;
        if (hp[10] % GGML_QNT_VERSION_FACTOR != GGML_FTYPE_MOSTLY_Q5_0) return false;
        const int32_t ftype = (hp[10] / GGML_QNT_VERSION_FACTOR) * GGML_QNT_VERSION_FACTOR + GGML_FTYPE_MOSTLY_Q8_0;
        segs_.push_back({Segment::COPY, 0, sizeof(magic) + 10 * sizeof(int32_t), {}});
        bytes(&ftype, sizeof(ftype));
        uint64_t copy_from = at;

        int32_t mel[2];
        if (!rd(mel, sizeof(mel)) || mel[0] < 0 || mel[1] < 0) return false;
        at += static_cast<uint64_t>(mel[0]) * static_cast<uint64_t>(mel[1]) * sizeof(float);
        int32_t n_vocab = 0;
        if (!rd(&n_vocab, sizeof(n_vocab)) || n_vocab < 0) return false;
        for (int32_t i = 0; i < n_vocab; ++i) {
            uint32_t len = 0;
            if (!rd(&len, sizeof(len))) return false;
            at += len;
        }

        while (at < size_) {
            const uint64_t head_at = at;
            int32_t head[3]; // n_dims, name length, type
            if (!rd(head, sizeof(head))) return false;
            if (head[0] < 0 || head[0] > 4 || head[1] < 0 || head[2] < 0 || head[2] >= GGML_TYPE_COUNT) return false;
            int32_t ne[4] = {1, 1, 1, 1};
            if (!rd(ne, sizeof(int32_t) * static_cast<size_t>(head[0]))) return false;
            at += static_cast<uint64_t>(head[1]);
            int64_t nel = 1;
            for (int i = 0; i < head[0]; ++i) nel *= ne[i];
            const auto type = static_cast<ggml_type>(head[2]);
            if (nel < 0 || nel % ggml_blck_size(type) != 0) return false;
            const uint64_t nbytes = static_cast<uint64_t>(nel / ggml_blck_size(type)) * ggml_type_size(type);
            if (at + nbytes > size_) return false;
            if (type == GGML_TYPE_Q5_0) {
                // unchanged bytes up to this tensor, its header with the new type, its data converted
                segs_.push_back({Segment::COPY, copy_from, head_at - copy_from, {}});
                const int32_t h8[3] = {head[0], head[1], GGML_TYPE_Q8_0};
                bytes(h8, sizeof(h8));
                segs_.push_back({Segment::COPY, head_at + sizeof(head), at - head_at - sizeof(head), {}});
                segs_.push_back({Segment::CONVERT, at, nbytes / Q5_BLOCK * Q8_BLOCK, {}});
                ++converted_;
                copy_from = at + nbytes;
            }
            at += nbytes;
        }
        if (converted_ == 0) return false;
        segs_.push_back({Segment::COPY, copy_from, size_ - copy_from, {}});
        return true;
    }

    size_t read(void * output, size_t size) {
        auto * out = static_cast<uint8_t *>(output);
        size_t done = 0;
        while (done < size && seg_ < segs_.size() && !failed_) {
            const Segment & g = segs_[seg_];
            if (off_ == g.len) {
                ++seg_;
                off_ = 0;
                continue;
            }
            const size_t want = static_cast<size_t>(std::min<uint64_t>(g.len - off_, size - done));
            bool ok = true;
            switch (g.kind) {
                case Segment::BYTES:
                    std::memcpy(out + done, g.bytes.data() + off_, want);
                    break;
                case Segment::COPY:
                    ok = read_at(g.src + off_, out + done, want);
                    break;
                case Segment::CONVERT:
                    ok = convert(g, off_, out + done, want);
                    break;
            }
            if (!ok) {
                failed_ = true;
                break;
            }
            done += want;
            off_ += want;
        }
        if (done < size) eof_ = true;
        return done;
    }

    /** Fills [n] output bytes of segment [g] starting at output offset [from]. */
    bool convert(const Segment & g, uint64_t from, uint8_t * out, size_t n) {
        size_t w = 0;
        while (w < n) {
            const uint64_t o = from + w;
            const uint64_t block = o / Q8_BLOCK;
            const size_t in_block = static_cast<size_t>(o % Q8_BLOCK);
            if (in_block == 0 && n - w >= Q8_BLOCK) {
                // whole blocks straight into the output
                const size_t blocks = std::min<size_t>((n - w) / Q8_BLOCK, 1 << 15);
                in_.resize(blocks * Q5_BLOCK);
                if (!read_at(g.src + block * Q5_BLOCK, in_.data(), in_.size())) return false;
                convert_blocks(in_.data(), out + w, blocks);
                w += blocks * Q8_BLOCK;
            } else {
                // a read that starts or ends inside a block
                uint8_t q5[Q5_BLOCK];
                uint8_t q8[Q8_BLOCK];
                if (!read_at(g.src + block * Q5_BLOCK, q5, Q5_BLOCK)) return false;
                convert_blocks(q5, q8, 1);
                const size_t k = std::min(Q8_BLOCK - in_block, n - w);
                std::memcpy(out + w, q8 + in_block, k);
                w += k;
            }
        }
        return true;
    }
};

} // namespace whisper_q8
