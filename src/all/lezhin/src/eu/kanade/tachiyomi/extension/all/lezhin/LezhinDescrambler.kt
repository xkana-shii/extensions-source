package eu.kanade.tachiyomi.extension.all.lezhin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import kotlin.math.floor
import kotlin.math.sqrt

object LezhinDescrambler {

    fun descramble(
        input: ByteArray,
        episodeId: Int,
        gridSize: Int = 5,
    ): ByteArray {
        val bitmap = try {
            BitmapFactory.decodeByteArray(
                input,
                0,
                input.size,
            )
        } catch (_: Throwable) {
            null
        } ?: return input

        val permutation = try {
            generatePermutation(
                episodeId = episodeId,
                gridSize = gridSize,
            )
        } catch (_: Throwable) {
            bitmap.recycle()
            return input
        }

        val pieces = try {
            getPieces(
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                permutation = permutation,
            )
        } catch (_: Throwable) {
            bitmap.recycle()
            return input
        }

        val output = try {
            Bitmap.createBitmap(
                bitmap.width,
                bitmap.height,
                Bitmap.Config.ARGB_8888,
            )
        } catch (_: Throwable) {
            bitmap.recycle()
            return input
        }

        val canvas = Canvas(output)

        try {
            pieces.forEach { piece ->
                val source = piece.to
                val destination = piece.from

                canvas.drawBitmap(
                    bitmap,
                    Rect(
                        source.left,
                        source.top,
                        source.left + source.width,
                        source.top + source.height,
                    ),
                    Rect(
                        destination.left,
                        destination.top,
                        destination.left + destination.width,
                        destination.top + destination.height,
                    ),
                    null,
                )
            }
        } catch (_: Throwable) {
            output.recycle()
            bitmap.recycle()
            return input
        }

        val result = ByteArrayOutputStream().use { stream ->
            val success = output.compress(
                Bitmap.CompressFormat.PNG,
                100,
                stream,
            )

            if (success) {
                stream.toByteArray()
            } else {
                input
            }
        }

        output.recycle()
        bitmap.recycle()

        return result
    }

    private fun generatePermutation(
        episodeId: Int,
        gridSize: Int,
    ): IntArray {
        val totalTiles =
            gridSize * gridSize

        val indices =
            IntArray(totalTiles) { it }

        var state =
            episodeId.toLong()

        fun next(modulo: Int): Int {
            state =
                state xor
                (state ushr 12)

            state =
                state xor
                (state shl 25)

            state =
                state xor
                (state ushr 27)

            return (
                (state ushr 32) %
                    modulo.toLong()
                ).toInt()
        }

        for (i in indices.indices) {
            val j = next(totalTiles)

            val value = indices[i]

            indices[i] = indices[j]
            indices[j] = value
        }

        return indices
    }

    private fun getPieces(
        imageWidth: Int,
        imageHeight: Int,
        permutation: IntArray,
    ): List<PieceData> {
        val indexedPermutation =
            permutation
                .toMutableList()
                .apply {
                    add(size)
                    add(size + 1)
                }

        val gridSize = floor(
            sqrt(
                indexedPermutation
                    .size
                    .toDouble(),
            ),
        ).toInt()

        return indexedPermutation
            .mapIndexedNotNull { fromIndex, toIndex ->
                val from =
                    getTileBounds(
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        gridSize = gridSize,
                        tileIndex = fromIndex,
                    )
                        ?: return@mapIndexedNotNull null

                val to =
                    getTileBounds(
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        gridSize = gridSize,
                        tileIndex = toIndex,
                    )
                        ?: return@mapIndexedNotNull null

                PieceData(
                    from = from,
                    to = to,
                )
            }
    }

    private fun getTileBounds(
        imageWidth: Int,
        imageHeight: Int,
        gridSize: Int,
        tileIndex: Int,
    ): Piece? {
        val totalTiles =
            gridSize * gridSize

        if (tileIndex < totalTiles) {
            val tileWidth =
                imageWidth / gridSize

            val tileHeight =
                imageHeight / gridSize

            if (
                tileWidth <= 0 ||
                tileHeight <= 0
            ) {
                return null
            }

            return Piece(
                left =
                (tileIndex % gridSize) *
                    tileWidth,
                top =
                (tileIndex / gridSize) *
                    tileHeight,
                width = tileWidth,
                height = tileHeight,
            )
        }

        if (tileIndex == totalTiles) {
            val remainderWidth =
                imageWidth % gridSize

            if (remainderWidth == 0) {
                return null
            }

            return Piece(
                left =
                imageWidth -
                    remainderWidth,
                top = 0,
                width = remainderWidth,
                height = imageHeight,
            )
        }

        val remainderHeight =
            imageHeight % gridSize

        if (remainderHeight == 0) {
            return null
        }

        return Piece(
            left = 0,
            top =
            imageHeight -
                remainderHeight,
            width =
            imageWidth -
                imageWidth % gridSize,
            height = remainderHeight,
        )
    }

    private class Piece(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

    private class PieceData(
        val from: Piece,
        val to: Piece,
    )
}
