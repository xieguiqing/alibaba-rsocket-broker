package com.alibaba.rsocket.encoding.impl;

import com.alibaba.rsocket.encoding.EncodingException;
import com.alibaba.rsocket.encoding.ObjectEncodingHandler;
import com.alibaba.rsocket.metadata.RSocketMimeType;
import com.alibaba.rsocket.observability.RsocketErrorCode;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.MessageLite;
import io.netty.buffer.*;
import io.netty.util.ReferenceCountUtil;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.Serializable;
import kotlinx.serialization.SerializationStrategy;
import kotlinx.serialization.protobuf.ProtoBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Object protobuf encoding
 *
 * @author leijuan
 */
@SuppressWarnings("unchecked")
public class ObjectEncodingHandlerProtobufImpl extends KotlinSerializerSupport implements ObjectEncodingHandler {
    LoadingCache<Class<?>, Method> parseFromMethodStore = Caffeine.newBuilder()
            .maximumSize(Integer.MAX_VALUE)
            .build(targetClass -> targetClass.getMethod("parseFrom", ByteBuffer.class));
    private boolean ktProtoBuf = true;

    public ObjectEncodingHandlerProtobufImpl() {
        try {
            Class.forName("kotlinx.serialization.protobuf.ProtoBuf");
        } catch (Exception e) {
            ktProtoBuf = false;
        }
    }

    @NotNull
    @Override
    public RSocketMimeType mimeType() {
        return RSocketMimeType.Protobuf;
    }

    @Override
    public ByteBuf encodingParams(@Nullable Object[] args) throws EncodingException {
        if (args != null && args.length == 1) {
            return encodingResult(args[0]);
        }
        return EMPTY_BUFFER;
    }

    @Override
    @Nullable
    public Object decodeParams(ByteBuf data, @Nullable Class<?>... targetClasses) throws EncodingException {
        if (data.readableBytes() > 0 && targetClasses != null && targetClasses.length == 1) {
            return decodeResult(data, targetClasses[0]);
        }
        return null;
    }

    @Override
    @NotNull
    public ByteBuf encodingResult(@Nullable Object result) throws EncodingException {
        if (result != null) {
            ByteBuf byteBuf = PooledByteBufAllocator.DEFAULT.buffer();
            try {
                ByteBufOutputStream bos = new ByteBufOutputStream(byteBuf);
                if (result instanceof MessageLite) {
                    ((MessageLite) result).writeTo(bos);
                } else if (ktProtoBuf && result.getClass().getAnnotation(Serializable.class) != null) {
                    byte[] bytes = ProtoBuf.Default.dump((SerializationStrategy<? super Object>) getSerializer(result.getClass()), result);
                    return Unpooled.wrappedBuffer(bytes);
                } else {
                    LinkedBuffer buffer = LinkedBuffer.allocate(256);
                    Schema schema = RuntimeSchema.getSchema(result.getClass());
                    ProtostuffIOUtil.writeTo(bos, result, schema, buffer);
                }
                return byteBuf;
            } catch (Exception e) {
                ReferenceCountUtil.safeRelease(byteBuf);
                throw new EncodingException(RsocketErrorCode.message("RST-700500", result.getClass().getCanonicalName(), "bytebuf"), e);
            }
        }
        return EMPTY_BUFFER;
    }

    @Override
    @Nullable
    public Object decodeResult(ByteBuf data, @Nullable Class<?> targetClass) throws EncodingException {
        if (data.readableBytes() > 0 && targetClass != null) {
            try {
                if (GeneratedMessageV3.class.isAssignableFrom(targetClass)) {
                    Method method = parseFromMethodStore.get(targetClass);
                    if (method != null) {
                        return method.invoke(null, data.nioBuffer());
                    }
                } else if (ktProtoBuf && targetClass.getAnnotation(Serializable.class) != null) {
                    byte[] bytes = new byte[data.readableBytes()];
                    data.readBytes(bytes);
                    return ProtoBuf.Default.load(getSerializer(targetClass), bytes);
                } else {
                    Schema schema = RuntimeSchema.getSchema(targetClass);
                    Object object = schema.newMessage();
                    ProtostuffIOUtil.mergeFrom(new ByteBufInputStream(data), object, schema);
                    return object;
                }
            } catch (Exception e) {
                throw new EncodingException(RsocketErrorCode.message("RST-700501", "bytebuf", targetClass.getName()), e);
            }
        }
        return null;
    }

}
