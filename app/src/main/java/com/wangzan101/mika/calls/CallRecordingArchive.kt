package com.wangzan101.mika.calls

import com.wangzan101.mika.settings.KeyCipher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Uses the existing AES-GCM cipher in small authenticated chunks rather than loading a 38 MB WAV.
 * Each block is bound to this recording/sequence; an authenticated trailer detects truncation.
 */
internal class CallRecordingArchive(private val cipher: KeyCipher) {
    // The Keystore factory lazily creates the alias on first use. Serializing each bounded
    // cipher operation prevents concurrent first-use key replacement without locking file I/O.
    private val cryptoLock = Any()
    fun encrypt(id: String, wav: File, destination: File) {
        require(CallRecordingLimits.validId(id) && wav.length() in 44..CallRecordingLimits.MAX_WAV_BYTES)
        validateWav(wav)
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        val uuid = UUID.fromString(id)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_BYTES)
        try {
            FileOutputStream(temporary).use { file ->
                val output = DataOutputStream(file)
                output.write(MAGIC)
                var sequence = 0
                var total = 0L
                wav.inputStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(count > 0)
                        digest.update(buffer, 0, count)
                        total += count
                        check(total <= CallRecordingLimits.MAX_WAV_BYTES)
                        writeBlock(output, uuid, sequence++, DATA, buffer, count)
                    }
                }
                val trailer = ByteBuffer.allocate(8 + 32).putLong(total).put(digest.digest()).array()
                writeBlock(output, uuid, sequence, END, trailer, trailer.size)
                output.flush()
                file.fd.sync()
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            buffer.fill(0)
            temporary.delete()
        }
    }

    fun decrypt(id: String, archive: File, wav: File) {
        require(CallRecordingLimits.validId(id) && archive.length() in 1..MAX_ARCHIVE_BYTES)
        val expected = UUID.fromString(id)
        val digest = MessageDigest.getInstance("SHA-256")
        var sequence = 0
        var total = 0L
        try {
            DataInputStream(archive.inputStream().buffered()).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                check(magic.contentEquals(MAGIC))
                FileOutputStream(wav).use { output ->
                    while (true) {
                        val encryptedSize = input.readInt()
                        check(encryptedSize in 1..MAX_ENCRYPTED_CHUNK)
                        val encrypted = ByteArray(encryptedSize)
                        input.readFully(encrypted)
                        val plain = synchronized(cryptoLock) { cipher.decrypt(encrypted) }
                        try {
                            check(plain.size in BLOCK_HEADER..(BLOCK_HEADER + CHUNK_BYTES))
                            val block = DataInputStream(ByteArrayInputStream(plain))
                            val type = block.readUnsignedByte()
                            check(block.readLong() == expected.mostSignificantBits && block.readLong() == expected.leastSignificantBits)
                            check(block.readInt() == sequence++)
                            val payloadBytes = plain.size - BLOCK_HEADER
                            when (type) {
                                DATA -> {
                                    check(payloadBytes > 0)
                                    total += payloadBytes
                                    check(total <= CallRecordingLimits.MAX_WAV_BYTES)
                                    output.write(plain, BLOCK_HEADER, payloadBytes)
                                    digest.update(plain, BLOCK_HEADER, payloadBytes)
                                }
                                END -> {
                                    check(payloadBytes == 40 && total >= 44 && block.readLong() == total)
                                    val expectedDigest = ByteArray(32)
                                    block.readFully(expectedDigest)
                                    check(MessageDigest.isEqual(expectedDigest, digest.digest()) && input.read() == -1)
                                    break
                                }
                                else -> error("Invalid recording block")
                            }
                        } finally {
                            plain.fill(0)
                        }
                    }
                }
            }
            validateWav(wav)
        } catch (error: Exception) {
            wav.delete()
            throw error
        }
    }

    private fun writeBlock(output: DataOutputStream, id: UUID, sequence: Int, type: Int, bytes: ByteArray, count: Int) {
        val plain = ByteArrayOutputStream(BLOCK_HEADER + count).apply {
            DataOutputStream(this).apply { writeByte(type); writeLong(id.mostSignificantBits); writeLong(id.leastSignificantBits); writeInt(sequence); write(bytes, 0, count) }
        }.toByteArray()
        try {
            val encrypted = synchronized(cryptoLock) { cipher.encrypt(plain) }
            check(encrypted.size in 1..MAX_ENCRYPTED_CHUNK)
            output.writeInt(encrypted.size)
            output.write(encrypted)
        } finally {
            plain.fill(0)
        }
    }

    private fun validateWav(file: File) {
        check(file.length() in 44..CallRecordingLimits.MAX_WAV_BYTES)
        val header = ByteArray(44)
        file.inputStream().use { DataInputStream(it).readFully(header) }
        val values = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        check(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 8, Charsets.US_ASCII) == "WAVEfmt ")
        check(values.getInt(16) == 16 && values.getShort(20).toInt() == 1 && values.getShort(22).toInt() == 2)
        check(values.getInt(24) == 16_000 && values.getInt(28) == 64_000 && values.getShort(32).toInt() == 4 && values.getShort(34).toInt() == 16)
        check(String(header, 36, 4, Charsets.US_ASCII) == "data" && values.getInt(40).toLong() == file.length() - 44)
        check(values.getInt(4).toLong() == file.length() - 8)
    }

    companion object {
        private val MAGIC = "FPCREC01".toByteArray(Charsets.US_ASCII)
        private const val CHUNK_BYTES = 64 * 1024
        private const val BLOCK_HEADER = 1 + 16 + 4
        private const val MAX_ENCRYPTED_CHUNK = CHUNK_BYTES + BLOCK_HEADER + 1024
        private const val DATA = 1
        private const val END = 2
        const val MAX_ARCHIVE_BYTES = CallRecordingLimits.MAX_WAV_BYTES + 1024 * 1024
    }
}
