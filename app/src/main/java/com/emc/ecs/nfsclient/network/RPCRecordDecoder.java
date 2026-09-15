/**
 * Copyright 2016-2018 Dell Inc. or its subsidiaries. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0.txt
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
/*
 * Vendored from EMCECS/nfs-client-java tag v1.1.0 (commit 0703a385, file text unchanged on
 * master as of 2026-09-15), the source of com.emc.ecs:nfs-client:1.1.0.
 * app/build.gradle.kts drops this class from the jar so only this copy is compiled.
 *
 * Upstream decode() consumes each fragment with skipBytes() and rewinds
 * readerIndex() - _recordLength on the last one, which assumes netty's cumulation buffer
 * still holds fragments decode() already consumed. It does not: FrameDecoder.updateCumulation
 * drops the consumed prefix after every socket read, so the rewind goes below zero and an
 * IndexOutOfBoundsException kills the connection. Upstream therefore only works when every
 * fragment of a record arrives in one cumulation pass, which is why single-fragment replies
 * were always fine and why the rig's five-fragment READ reply read back zero bytes.
 * Deviations: a _fragments field with its two imports, and in decode() each fragment is copied
 * out of the cumulation buffer into it, record mark included, instead of being skipped over,
 * with the last fragment assembling _fragments rather than rewinding and a single-fragment
 * record handed over as the one array already copied; the comment on the non-last-fragment
 * branch now names this decoder rather than the cumulation buffer. MAX_RECORD_LENGTH bounds
 * what that accumulation can reach, throwing TooLongFrameException, with that import and the
 * Locale one its message is formatted under; it is package-private rather than private so the
 * test in this package can assert it against the app's read ceiling, and it is tested on the
 * length a record mark declares, ahead of upstream's wait for that fragment's bytes, so the
 * local recordLength sum replaces the post-copy _recordLength += there. cleanup(), with its
 * import, drops a half-received record.
 */
package com.emc.ecs.nfsclient.network;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.handler.codec.frame.FrameDecoder;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * To receive the entire response. We do not actually decode the rpc packet here.
 * Just get the size from the packet and then put them in internal buffer until all data arrive.
 * 
 * @author seibed
 */
public class RPCRecordDecoder extends FrameDecoder {

    /**
     * Holds the calculated record length for each channel until the Channel is ready for buffering.
     * Reset to 0 after that for the next channel.
     */
    private int _recordLength = 0;

    /**
     * Ceiling on one record's accumulated length, four times the largest reply the app can
     * provoke; NFS_READ_CHUNK is 512 KiB, and RPCRecordDecoderTest asserts that relation, so
     * raising the read ceiling past this fails there instead of on a server's first full-size
     * reply. Package-private so that test can name it.
     *
     * It is tested against the length a record mark DECLARES, before that fragment's bytes are
     * waited for: a record cannot grow past the ceiling, and no single oversized fragment - a
     * mark claiming 2 GiB included - is cumulated and copied before being refused, which is
     * what upstream and the first version of this cap both did. It still does not bound a
     * fragment under the ceiling; FrameDecoder cumulates those as upstream cumulated them, so
     * one record in flight can cost the ceiling in the cumulation buffer plus the copy out of
     * it.
     */
    static final int MAX_RECORD_LENGTH = 2 * 1024 * 1024;

    /**
     * The fragments of the record currently being received, record marks included, in arrival
     * order. Connection gives every channel its own decoder instance, so this is per-connection.
     */
    private final List<byte[]> _fragments = new ArrayList<byte[]>();

    /* (non-Javadoc)
     * @see org.jboss.netty.handler.codec.frame.FrameDecoder#decode(org.jboss.netty.channel.ChannelHandlerContext, org.jboss.netty.channel.Channel, org.jboss.netty.buffer.ChannelBuffer)
     */
    protected Object decode(ChannelHandlerContext channelHandlerContext, Channel channel, ChannelBuffer channelBuffer) throws Exception {
        // Wait until the length prefix is available.
        if (channelBuffer.readableBytes() < 4) {
            // If null is returned, it means there is not enough data yet.
            // FrameDecoder will call again when there is a sufficient amount of data available.
            return null;
        }

        //marking the current reading position
        channelBuffer.markReaderIndex();

        //get the fragment size and wait until the entire fragment is available.
        long fragSize = channelBuffer.readUnsignedInt();
        boolean lastFragment = RecordMarkingUtil.isLastFragment(fragSize);
        fragSize = RecordMarkingUtil.maskFragmentSize(fragSize);
        long recordLength = _recordLength + 4 + fragSize;
        if (recordLength > MAX_RECORD_LENGTH) {
            String message = String.format(Locale.ROOT,
                    "RPC record of %d bytes exceeds the %d byte maximum",
                    recordLength, MAX_RECORD_LENGTH);
            channelBuffer.resetReaderIndex();
            _fragments.clear();
            _recordLength = 0;
            throw new TooLongFrameException(message);
        }
        if (channelBuffer.readableBytes() < fragSize) {
            channelBuffer.resetReaderIndex();
            return null;
        }

        byte[] fragment = new byte[4 + (int) fragSize];
        channelBuffer.resetReaderIndex();
        channelBuffer.readBytes(fragment);
        _fragments.add(fragment);
        _recordLength = (int) recordLength;

        //check the last fragment
        if (!lastFragment) {
            //not the last fragment, the data is kept in this decoder until the record completes
            return null;
        }

        byte[] rpcResponse;
        if (_fragments.size() == 1) {
            rpcResponse = fragment;
        } else {
            rpcResponse = new byte[_recordLength];
            int offset = 0;
            for (byte[] received : _fragments) {
                System.arraycopy(received, 0, rpcResponse, offset, received.length);
                offset += received.length;
            }
        }

        _fragments.clear();
        _recordLength = 0;
        return rpcResponse;
    }

    /**
     * Both channelDisconnected and channelClosed funnel through here. Connection builds a
     * fresh decoder per channel, so a half-received record cannot reach a later one; this
     * releases it as soon as the pipeline dies instead of waiting for collection, and keeps
     * the class correct under a future pipeline that shares the handler.
     *
     * @see org.jboss.netty.handler.codec.frame.FrameDecoder#cleanup(org.jboss.netty.channel.ChannelHandlerContext, org.jboss.netty.channel.ChannelStateEvent)
     */
    @Override
    protected void cleanup(ChannelHandlerContext channelHandlerContext, ChannelStateEvent channelStateEvent) throws Exception {
        try {
            super.cleanup(channelHandlerContext, channelStateEvent);
        } finally {
            _fragments.clear();
            _recordLength = 0;
        }
    }
}
