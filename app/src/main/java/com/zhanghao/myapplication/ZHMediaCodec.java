package com.zhanghao.myapplication;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Created by zhang hao on 2023/12/08.
 * 模拟H264的解析
 * 目的：解析SPS
 */
public class ZHMediaCodec implements Runnable {

    private final String TAG = "ZHMediaCodec";

    private InputStream dataInputStream;
    private byte[] dataByteArray;
    private int offsetIndex;   // 指针偏移量
    private int totalSize;     // 文件总大小
    private int nalBitIndex = 0;        // Nal的指针下标

    public ZHMediaCodec(InputStream dataInputStream) {
        this.dataInputStream = dataInputStream;
    }

    public void startCodec() {
        new Thread(this).start();
    }

    /**
     * 寻找分隔符坐标 :
     * 0x 00 00 01
     * 0x 00 00 00 01
     * 找到后移动指针到分隔符后的第一个字节
     */
    private int findSeparator() {
        for (int i = offsetIndex; i < totalSize - 4; i++) {
            if (dataByteArray[i] == 0x00 && dataByteArray[i + 1] == 0x00 && dataByteArray[i + 2] == 0x01) {
                offsetIndex = i + 3;
                return i;
            }
            if (dataByteArray[i] == 0x00 && dataByteArray[i + 1] == 0x00 && dataByteArray[i + 2] == 0x00 && dataByteArray[i + 3] == 0x01) {
                offsetIndex = i + 4;
                return i;
            }
        }
        return -1;
    }

    /**
     * 读取数据到byte数组中;
     * 未进行分批处理，因此文件不能太大
     */
    private void readDataToByte() {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = dataInputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
            }
            dataInputStream.close();

            dataByteArray = byteArrayOutputStream.toByteArray();
            byteArrayOutputStream.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取一个NALU单元字节数组
     */
    private byte[] getNalu(int offsetIndex, int length) {
        byte[] naluByteArray = new byte[length];
        for (int i = offsetIndex; i < offsetIndex + length; i++) {
            naluByteArray[i - offsetIndex] = dataByteArray[i];
        }
        return naluByteArray;
    }

    /**
     * 获取指定长度bit的值；
     * 获取完毕后，将指针移动到读取后的位置。
     *
     * @param length 需要读取的bit位长度
     * @param nal    NALU单元
     * @return 读取到的值, 以整数返回
     */
    private int u(int length, byte[] nal) {
        int data = 0;
        for (int i = nalBitIndex; i < nalBitIndex + length; i++) {
            // 将data左移一位，为下一个比特腾出位置
            data <<= 1;

            // 将nal[i]右移（7 - i % 8）位，获取当前比特的值
            int bitValue = (nal[i / 8] >> (7 - i % 8)) & 0x01;

            // 将当前比特的值加到data的最低位
            data |= bitValue;
        }

        // 更新 nalBitIndex 指针位置
        nalBitIndex += length;
        return data;
    }

    /**
     * 辅助方法，用于解析 ue(v) 类型的 Exp-Golomb 编码
     * 解析无符号数
     *
     * @param nal NAL 单元
     * @return 解析Exp-Golomb编码后的值
     */
    private int ue(byte[] nal) {
        int leadingZeros = 0;
        while (u(1, nal) == 0 && leadingZeros < 32) {
            leadingZeros++;
        }
        return (1 << leadingZeros) - 1 + u(leadingZeros, nal);
    }

    /**
     * 辅助方法，用于解析 se(v) 类型的 Exp-Golomb 编码
     * 解析有符号数
     *
     * @param nal NAL 单元
     * @return 解析Exp-Golomb编码后的值
     */
    private int se(byte[] nal) {
        int value = ue(nal);
        return (value % 2 == 0) ? -(value / 2) : (value + 1) / 2;
    }

    @Override
    public void run() {
        readDataToByte();
        offsetIndex = 0;
        totalSize = dataByteArray.length;

        // 顺带解析一下GOP的长度
        int gopLength = 0;

        // NALU 单元的起始位置和结束位置坐标
        int nal_start;
        int nal_end;

        while (true) {
            if (totalSize == 0 || offsetIndex >= totalSize) {
                Log.e(TAG, "文件长度为0   或者  文件已经读取完毕");
                break;
            }

            nal_start = offsetIndex;
            // 找到分隔符的起始坐标
            int separatorIndex = findSeparator();
            nal_end = separatorIndex;
            if (separatorIndex == 0) {
                Log.i(TAG, "run: 文件开头的分隔符,不处理");
                continue;
            }

            if (separatorIndex == -1) {
                // 只关注SPS的解析， 因此最后一段数据暂不处理
                Log.i(TAG, "run: 文件末尾无分隔符,剩余长度为 totalSize - offsetIndex = " + (totalSize - offsetIndex));
                break;
            }

            // nal单元字节数组
            byte[] nalu = getNalu(nal_start, nal_end - nal_start);

            // 获取NAL类型
            byte nalType = (byte) (nalu[0] & 0x1F);
            switch (nalType) {
                case 1: //  非IDR
                    gopLength++;
                    break;
                case 5: //  IDR
                    if (gopLength != 0) {
//                        Log.i(TAG, "run: gop_length = " + gopLength);
                    }
                    gopLength = 1;
                    break;
                case 7: //  SPS
                    parseSps(nalu);
                    break;
                case 8: //  PPS
                    break;
            }
        }
    }

    /**
     * 解析SPS
     * H264 码流中， 为了压缩空间，数据含义不再以字节为单位，而是以 位 为单位；
     * 可变长编码：Exp-Golomb指数哥伦布编码;
     * 各个数据代表的含义见文件末尾！
     */
    private void parseSps(byte[] nal) {
        Log.i(TAG, "parseSps: SPS start--------------------------------");

        // 在NALU单元中跳过前1个字节，因为这些字节的后五位代表的NALU的类型
        nalBitIndex = 8;

        // 码流的profile
        int profile_idc = u(8, nal);

        // 视频序列的 profile
        int constraint_set0_flag = u(1, nal);
        int constraint_set1_flag = u(1, nal);
        int constraint_set2_flag = u(1, nal);
        int constraint_set3_flag = u(1, nal);

        // 保留位
        int reserved_zero_4bits = u(4, nal);

        // 视频序列的 level
        int level_idc = u(8, nal);

        // 当前SPS的ID
        int seq_parameter_set_id = ue(nal);

        Log.i(TAG, "parseSps: profile_idc = " + profile_idc);
        Log.i(TAG, "parseSps: constraint_set0_flag = " + constraint_set0_flag);
        Log.i(TAG, "parseSps: constraint_set1_flag = " + constraint_set1_flag);
        Log.i(TAG, "parseSps: constraint_set2_flag = " + constraint_set2_flag);
        Log.i(TAG, "parseSps: constraint_set3_flag = " + constraint_set3_flag);
        Log.i(TAG, "parseSps: reserved_zero_4bits = " + reserved_zero_4bits);
        Log.i(TAG, "parseSps: level_idc = " + level_idc);
        Log.i(TAG, "parseSps: seq_parameter_set_id = " + seq_parameter_set_id);

        if (profile_idc == 100 || profile_idc == 110 ||
                profile_idc == 122 || profile_idc == 244 || profile_idc == 44 ||
                profile_idc == 83 || profile_idc == 86 || profile_idc == 118 ||
                profile_idc == 128) {

            // 当前的采样方式  如 YUV420  YUV422  YUV444
            int chroma_format_idc = ue(nal);
            Log.i(TAG, "parseSps: chroma_format_idc = " + chroma_format_idc);
            if (chroma_format_idc == 3) {
                // 在YUV444的时候， 用于指示是否对每个色度平面进行单独编码
                int separate_colour_plane_flag = u(1, nal);
                Log.i(TAG, "parseSps: separate_colour_plane_flag = " + separate_colour_plane_flag);
            }

            // 亮度样本位深度
            int bit_depth_luma_minus8 = ue(nal);
            // 色度样本位深度
            int bit_depth_chroma_minus8 = ue(nal);
            // 变换旁路
            int qpprime_y_zero_transform_bypass_flag = u(1, nal);

            Log.i(TAG, "parseSps: bit_depth_luma_minus8 = " + bit_depth_luma_minus8);
            Log.i(TAG, "parseSps: bit_depth_chroma_minus8 = " + bit_depth_chroma_minus8);
            Log.i(TAG, "parseSps: qpprime_y_zero_transform_bypass_flag = " + qpprime_y_zero_transform_bypass_flag);

            // 是否存在序列级别缩放矩阵
            int seq_scaling_matrix_present_flag = u(1, nal);
            if (seq_scaling_matrix_present_flag == 1) {
                for (int i = 0; i < ((chroma_format_idc != 3) ? 8 : 12); i++) {
                    // 是否考虑缩放
                    int seq_scaling_list_present_flag = u(1, nal);
                    Log.i(TAG, "parseSps: seq_scaling_list_present_flag = " + seq_scaling_list_present_flag);
                }
            }
        }

        // 表示最大帧号的参数之一
        int log2_max_frame_num_minus4 = ue(nal);

        // 图像的显示顺序计数器
        int pic_order_cnt_type = ue(nal);

        Log.i(TAG, "parseSps: log2_max_frame_num_minus4 = " + log2_max_frame_num_minus4);
        Log.i(TAG, "parseSps: pic_order_cnt_type = " + pic_order_cnt_type);

        if (pic_order_cnt_type == 0) {
            // 图像的显示顺序计数器
            int log2_max_pic_order_cnt_lsb_minus4 = ue(nal);
            Log.i(TAG, "parseSps: log2_max_pic_order_cnt_lsb_minus4 = " + log2_max_pic_order_cnt_lsb_minus4);
        } else if (pic_order_cnt_type == 1) {
            int delta_pic_order_always_zero_flag = u(1, nal);
            int offset_for_non_ref_pic = se(nal);
            int offset_for_top_to_bottom_field = se(nal);
            int num_ref_frames_in_pic_order_cnt_cycle = ue(nal);

            Log.i(TAG, "parseSps: delta_pic_order_always_zero_flag = " + delta_pic_order_always_zero_flag);
            Log.i(TAG, "parseSps: offset_for_non_ref_pic = " + offset_for_non_ref_pic);
            Log.i(TAG, "parseSps: offset_for_top_to_bottom_field = " + offset_for_top_to_bottom_field);
            Log.i(TAG, "parseSps: num_ref_frames_in_pic_order_cnt_cycle = " + num_ref_frames_in_pic_order_cnt_cycle);

            for (int i = 0; i < num_ref_frames_in_pic_order_cnt_cycle; i++) {
                int offset_for_ref_frame = se(nal);
                Log.i(TAG, "parseSps: offset_for_ref_frame = " + offset_for_ref_frame);
            }
        }
        // 最大参考帧数
        int max_num_ref_frames = ue(nal);

        // 图像帧号中是否允许有间隔
        int gaps_in_frame_num_value_allowed_flag = u(1, nal);

        // 宽高
        int pic_width_in_mbs_minus1 = ue(nal);
        int pic_height_in_map_units_minus1 = ue(nal);

        Log.i(TAG, "parseSps: max_num_ref_frames = " + max_num_ref_frames);
        Log.i(TAG, "parseSps: gaps_in_frame_num_value_allowed_flag = " + gaps_in_frame_num_value_allowed_flag);
        Log.i(TAG, "parseSps: pic_width_in_mbs_minus1 = " + pic_width_in_mbs_minus1);
        Log.i(TAG, "parseSps: pic_height_in_map_units_minus1 = " + pic_height_in_map_units_minus1);

        // 用于指示图像帧是否只包含宏块（Macroblocks，MBs），而没有场（Field)
        int frame_mbs_only_flag = u(1, nal);
        if (frame_mbs_only_flag == 0) {
            int mb_adaptive_frame_field_flag = u(1, nal);
            Log.i(TAG, "parseSps: mb_adaptive_frame_field_flag = " + mb_adaptive_frame_field_flag);
        }

        // 帧间预测（inter-prediction）中是否启用了8x8的直接模式
        int direct_8x8_inference_flag = u(1, nal);
        Log.i(TAG, "parseSps: direct_8x8_inference_flag = " + direct_8x8_inference_flag);

        // 帧裁剪标志
        int frame_cropping_flag = u(1, nal);
        if (frame_cropping_flag == 1) {
            int frame_crop_left_offset = ue(nal);
            int frame_crop_right_offset = ue(nal);
            int frame_crop_top_offset = ue(nal);
            int frame_crop_bottom_offset = ue(nal);

            Log.i(TAG, "parseSps: frame_crop_left_offset = " + frame_crop_left_offset);
            Log.i(TAG, "parseSps: frame_crop_right_offset = " + frame_crop_right_offset);
            Log.i(TAG, "parseSps: frame_crop_top_offset = " + frame_crop_top_offset);
            Log.i(TAG, "parseSps: frame_crop_bottom_offset = " + frame_crop_bottom_offset);
        }

        // 用于指示是否存在视频可选用户界面（VUI）参数
        int vui_parameters_present_flag = u(1, nal);
        Log.i(TAG, "parseSps: vui_parameters_present_flag = " + vui_parameters_present_flag);
        if (vui_parameters_present_flag == 1) {
            // 解析VUI参数
        }

        Log.i(TAG, "parseSps: SPS end--------------------------------");
    }
}

/**
 * SPS 字段含义
 * 参考：工程目录下[SPS字段含义.webp]
 * profile_idc : 标识当前H.264码流的profile   （8 bit）
 * -----基准档次（Baseline Profile）：基本画质，支持I/P帧，只支持无交错（Progressive）和CAVLC12。profile_idc值为66。
 * -----主要档次（Main Profile）：主流画质，提供I/P/B帧，支持无交错（Progressive）和交错（Interlaced），也支持CAVLC和CABAC12。profile_idc值为77。
 * -----扩展档次（Extended Profile）：进阶画质，支持I/P/B/SP/SI帧，只支持无交错（Progressive）和CAVLC12。profile_idc值为882。
 * -----高级档次（High Profile）：高级画质，在Main Profile的基础上增加了8x8内部预测、自定义量化、无损视频编码和更多的YUV格式12。profile_idc值为100。
 * constraint_set0_flag: 用于标识是否使用了约束集。  (1 bit)
 * constraint_set2_flag:  用于标识是否使用了约束集。 (1 bit)
 * constraint_set1_flag:  用于标识是否使用了约束集。 (1 bit)
 * constraint_set3_flag:  用于标识是否使用了约束集。 (1 bit)
 * -----分别代表四个约束集的标志,例如支持的图像分辨率、采样格式、帧率等。这些标志为 1 表示编码器遵循了对应的约束集，为 0 表示未遵循.
 * reserved_zero_4bits: 保留位，必须为 0. (4 bit)
 * level_idc: 标识当前H.264码流的level, 值为等级的10倍   （8 bit）
 * -----Level 1：level_idc值为10
 * -----Level 1b：level_idc值为11且constraint_set3_flag等于1
 * -----Level 1.1：level_idc值为11
 * -----Level 1.2：level_idc值为12
 * -----Level 1.3：level_idc值为13
 * -----Level 2：level_idc值为20
 * -----Level 2.1：level_idc值为21
 * -----Level 2.2：level_idc值为22
 * -----Level 3：level_idc值为30
 * -----Level 3.1：level_idc值为31
 * -----Level 3.2：level_idc值为32
 * -----Level 4：level_idc值为40
 * -----Level 4.1：level_idc值为41
 * -----Level 4.2：level_idc值为42
 * -----Level 5：level_idc值为50
 * -----Level 5.1：level_idc值为51
 * seq_parameter_set_id: 用于标识当前SPS的ID。（可变长编码）
 * -----用于区分不同的 SPS
 * chroma_format_idc: 用于指定与亮度取样对应的色度取样, 即YUV44 YUV422 YUV420。（可变长编码）
 * ----- 在profile_idc == 100 时，chroma_format_idc存在于SPS中
 * ----- 当 chroma_format_idc不存在时，应推断其值为 1
 * ----- 取值范围为0~3，分别代表 ： 单色 YUV420 YUV422 YUV444
 * separate_colour_plane_flag: 用于指示是否对每个色度平面进行单独编码。（1 bit）
 * ----- 在profile_idc == 100 时，separate_colour_plane_flag存在于SPS中
 * ----- 取值范围为0~1，分别代表： 不单独编码  单独编码
 * bit_depth_luma_minus8: 亮度样本位深度。 位深度-8（可变长编码）
 * bit_depth_chroma_minus8： 色度样本位深度。 位深度-8（可变长编码）
 * ----- 值为：0代表位深度8， 2代表位深度10
 * qpprime_y_zero_transform_bypass_flag: 变换旁路。（1 bit）
 * ----- 取值范围为0~1，分别代表： 不变换  变换（相对高效的无损编码）
 * ----- 没有特别指定时，应推断其值为 0
 * seq_scaling_matrix_present_flag: 是否存在序列级别缩放矩阵。（1 bit）
 * ----- 取值范围为0~1，分别代表： 不存在  存在 , 会影响视频质量
 * ----- 没有特别指定时，应推断其值为 0
 * seq_scaling_list_present_flag: 同样也是是否考虑缩放    (1 bit)
 * log2_max_frame_num_minus4:  表示最大帧号的参数之一。（可变长编码）
 * pic_order_cnt_type: 图像的显示顺序计数器。（可变长编码）
 * ----- 当 pic_order_cnt_type 为 0 时，采用基本的 POC 计数方式。
 * ----- 当 pic_order_cnt_type 为 1 时，采用基于场的 POC 计数方式。
 * ----- 当 pic_order_cnt_type 为 2 时，采用基于图像的 POC 计数方式。
 * log2_max_pic_order_cnt_lsb_minus4:  图像的显示顺序计数器。（可变长编码）
 * ........
 * ........
 * pic_width_in_mbs_minus1:  图像的宽度-1。（可变长编码）
 * ----- (pic_width_in_mbs_minus1 + 1) * 16 = 图像的宽度。
 * pic_height_in_map_units_minus1: 图像的高度-1。（可变长编码）
 * ----- (pic_height_in_map_units_minus1 + 1) * 16 = 图像的高度。
 */

