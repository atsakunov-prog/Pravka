package ru.zf.slushalka.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Иконки читалки и полки - контуры Material (Apache 2.0), взятые поштучно из
 * material-icons-extended. Сама библиотека весит десятки мегабайт, а APK
 * собирается без R8: подключи её целиком - и в установку уехали бы все две
 * тысячи иконок ради десятка.
 */
object Glyphs {
    val Headphones: ImageVector by lazy {
        materialIcon(name = "Glyphs.Headphones") {
            materialPath {
                moveTo(12.0f, 3.0f)
                curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
                verticalLineToRelative(7.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(-8.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(-1.0f)
                curveToRelative(0.0f, -3.87f, 3.13f, -7.0f, 7.0f, -7.0f)
                reflectiveCurveToRelative(7.0f, 3.13f, 7.0f, 7.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(8.0f)
                horizontalLineToRelative(4.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-7.0f)
                curveTo(21.0f, 7.03f, 16.97f, 3.0f, 12.0f, 3.0f)
                close()
                moveTo(7.0f, 15.0f)
                verticalLineToRelative(4.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineTo(7.0f)
                close()
                moveTo(19.0f, 19.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(19.0f)
                close()
            }
        }
    }

    val RecordVoiceOver: ImageVector by lazy {
        materialIcon(name = "Glyphs.RecordVoiceOver") {
            materialPath {
                moveTo(9.0f, 13.0f)
                curveToRelative(2.21f, 0.0f, 4.0f, -1.79f, 4.0f, -4.0f)
                reflectiveCurveToRelative(-1.79f, -4.0f, -4.0f, -4.0f)
                reflectiveCurveToRelative(-4.0f, 1.79f, -4.0f, 4.0f)
                reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
                close()
                moveTo(9.0f, 7.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
                reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                reflectiveCurveToRelative(-2.0f, -0.9f, -2.0f, -2.0f)
                reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                close()
                moveTo(9.0f, 15.0f)
                curveToRelative(-2.67f, 0.0f, -8.0f, 1.34f, -8.0f, 4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(-2.0f)
                curveToRelative(0.0f, -2.66f, -5.33f, -4.0f, -8.0f, -4.0f)
                close()
                moveTo(3.0f, 19.0f)
                curveToRelative(0.22f, -0.72f, 3.31f, -2.0f, 6.0f, -2.0f)
                curveToRelative(2.7f, 0.0f, 5.8f, 1.29f, 6.0f, 2.0f)
                lineTo(3.0f, 19.0f)
                close()
                moveTo(15.08f, 7.05f)
                curveToRelative(0.84f, 1.18f, 0.84f, 2.71f, 0.0f, 3.89f)
                lineToRelative(1.68f, 1.69f)
                curveToRelative(2.02f, -2.02f, 2.02f, -5.07f, 0.0f, -7.27f)
                lineToRelative(-1.68f, 1.69f)
                close()
                moveTo(20.07f, 2.0f)
                lineToRelative(-1.63f, 1.63f)
                curveToRelative(2.77f, 3.02f, 2.77f, 7.56f, 0.0f, 10.74f)
                lineTo(20.07f, 16.0f)
                curveToRelative(3.9f, -3.89f, 3.91f, -9.95f, 0.0f, -14.0f)
                close()
            }
        }
    }

    val AutoAwesome: ImageVector by lazy {
        materialIcon(name = "Glyphs.AutoAwesome") {
            materialPath {
                moveTo(19.0f, 9.0f)
                lineToRelative(1.25f, -2.75f)
                lineToRelative(2.75f, -1.25f)
                lineToRelative(-2.75f, -1.25f)
                lineToRelative(-1.25f, -2.75f)
                lineToRelative(-1.25f, 2.75f)
                lineToRelative(-2.75f, 1.25f)
                lineToRelative(2.75f, 1.25f)
                close()
            }
            materialPath {
                moveTo(19.0f, 15.0f)
                lineToRelative(-1.25f, 2.75f)
                lineToRelative(-2.75f, 1.25f)
                lineToRelative(2.75f, 1.25f)
                lineToRelative(1.25f, 2.75f)
                lineToRelative(1.25f, -2.75f)
                lineToRelative(2.75f, -1.25f)
                lineToRelative(-2.75f, -1.25f)
                close()
            }
            materialPath {
                moveTo(11.5f, 9.5f)
                lineTo(9.0f, 4.0f)
                lineTo(6.5f, 9.5f)
                lineTo(1.0f, 12.0f)
                lineToRelative(5.5f, 2.5f)
                lineTo(9.0f, 20.0f)
                lineToRelative(2.5f, -5.5f)
                lineTo(17.0f, 12.0f)
                lineTo(11.5f, 9.5f)
                close()
                moveTo(9.99f, 12.99f)
                lineTo(9.0f, 15.17f)
                lineToRelative(-0.99f, -2.18f)
                lineTo(5.83f, 12.0f)
                lineToRelative(2.18f, -0.99f)
                lineTo(9.0f, 8.83f)
                lineToRelative(0.99f, 2.18f)
                lineTo(12.17f, 12.0f)
                lineTo(9.99f, 12.99f)
                close()
            }
        }
    }

    val MenuBook: ImageVector by lazy {
        materialIcon(name = "Glyphs.MenuBook") {
            materialPath {
                moveTo(21.0f, 5.0f)
                curveToRelative(-1.11f, -0.35f, -2.33f, -0.5f, -3.5f, -0.5f)
                curveToRelative(-1.95f, 0.0f, -4.05f, 0.4f, -5.5f, 1.5f)
                curveToRelative(-1.45f, -1.1f, -3.55f, -1.5f, -5.5f, -1.5f)
                reflectiveCurveTo(2.45f, 4.9f, 1.0f, 6.0f)
                verticalLineToRelative(14.65f)
                curveToRelative(0.0f, 0.25f, 0.25f, 0.5f, 0.5f, 0.5f)
                curveToRelative(0.1f, 0.0f, 0.15f, -0.05f, 0.25f, -0.05f)
                curveTo(3.1f, 20.45f, 5.05f, 20.0f, 6.5f, 20.0f)
                curveToRelative(1.95f, 0.0f, 4.05f, 0.4f, 5.5f, 1.5f)
                curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
                curveToRelative(1.65f, 0.0f, 3.35f, 0.3f, 4.75f, 1.05f)
                curveToRelative(0.1f, 0.05f, 0.15f, 0.05f, 0.25f, 0.05f)
                curveToRelative(0.25f, 0.0f, 0.5f, -0.25f, 0.5f, -0.5f)
                verticalLineTo(6.0f)
                curveTo(22.4f, 5.55f, 21.75f, 5.25f, 21.0f, 5.0f)
                close()
                moveTo(21.0f, 18.5f)
                curveToRelative(-1.1f, -0.35f, -2.3f, -0.5f, -3.5f, -0.5f)
                curveToRelative(-1.7f, 0.0f, -4.15f, 0.65f, -5.5f, 1.5f)
                verticalLineTo(8.0f)
                curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
                curveToRelative(1.2f, 0.0f, 2.4f, 0.15f, 3.5f, 0.5f)
                verticalLineTo(18.5f)
                close()
            }
            materialPath {
                moveTo(17.5f, 10.5f)
                curveToRelative(0.88f, 0.0f, 1.73f, 0.09f, 2.5f, 0.26f)
                verticalLineTo(9.24f)
                curveTo(19.21f, 9.09f, 18.36f, 9.0f, 17.5f, 9.0f)
                curveToRelative(-1.7f, 0.0f, -3.24f, 0.29f, -4.5f, 0.83f)
                verticalLineToRelative(1.66f)
                curveTo(14.13f, 10.85f, 15.7f, 10.5f, 17.5f, 10.5f)
                close()
            }
            materialPath {
                moveTo(13.0f, 12.49f)
                verticalLineToRelative(1.66f)
                curveToRelative(1.13f, -0.64f, 2.7f, -0.99f, 4.5f, -0.99f)
                curveToRelative(0.88f, 0.0f, 1.73f, 0.09f, 2.5f, 0.26f)
                verticalLineTo(11.9f)
                curveToRelative(-0.79f, -0.15f, -1.64f, -0.24f, -2.5f, -0.24f)
                curveTo(15.8f, 11.66f, 14.26f, 11.96f, 13.0f, 12.49f)
                close()
            }
            materialPath {
                moveTo(17.5f, 14.33f)
                curveToRelative(-1.7f, 0.0f, -3.24f, 0.29f, -4.5f, 0.83f)
                verticalLineToRelative(1.66f)
                curveToRelative(1.13f, -0.64f, 2.7f, -0.99f, 4.5f, -0.99f)
                curveToRelative(0.88f, 0.0f, 1.73f, 0.09f, 2.5f, 0.26f)
                verticalLineToRelative(-1.52f)
                curveTo(19.21f, 14.41f, 18.36f, 14.33f, 17.5f, 14.33f)
                close()
            }
        }
    }

    val EditNote: ImageVector by lazy {
        materialIcon(name = "Glyphs.EditNote") {
            materialPath {
                moveTo(3.0f, 10.0f)
                horizontalLineToRelative(11.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(3.0f)
                verticalLineTo(10.0f)
                close()
                moveTo(3.0f, 8.0f)
                horizontalLineToRelative(11.0f)
                verticalLineTo(6.0f)
                horizontalLineTo(3.0f)
                verticalLineTo(8.0f)
                close()
                moveTo(3.0f, 16.0f)
                horizontalLineToRelative(7.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(3.0f)
                verticalLineTo(16.0f)
                close()
                moveTo(18.01f, 12.87f)
                lineToRelative(0.71f, -0.71f)
                curveToRelative(0.39f, -0.39f, 1.02f, -0.39f, 1.41f, 0.0f)
                lineToRelative(0.71f, 0.71f)
                curveToRelative(0.39f, 0.39f, 0.39f, 1.02f, 0.0f, 1.41f)
                lineToRelative(-0.71f, 0.71f)
                lineTo(18.01f, 12.87f)
                close()
                moveTo(17.3f, 13.58f)
                lineToRelative(-5.3f, 5.3f)
                verticalLineTo(21.0f)
                horizontalLineToRelative(2.12f)
                lineToRelative(5.3f, -5.3f)
                lineTo(17.3f, 13.58f)
                close()
            }
        }
    }

    val Image: ImageVector by lazy {
        materialIcon(name = "Glyphs.Image") {
            materialPath {
                moveTo(19.0f, 5.0f)
                verticalLineToRelative(14.0f)
                lineTo(5.0f, 19.0f)
                lineTo(5.0f, 5.0f)
                horizontalLineToRelative(14.0f)
                moveToRelative(0.0f, -2.0f)
                lineTo(5.0f, 3.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(21.0f, 5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(14.14f, 11.86f)
                lineToRelative(-3.0f, 3.87f)
                lineTo(9.0f, 13.14f)
                lineTo(6.0f, 17.0f)
                horizontalLineToRelative(12.0f)
                lineToRelative(-3.86f, -5.14f)
                close()
            }
        }
    }

    val TextFields: ImageVector by lazy {
        materialIcon(name = "Glyphs.TextFields") {
            materialPath {
                moveTo(2.5f, 4.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(12.0f)
                horizontalLineToRelative(3.0f)
                lineTo(10.5f, 7.0f)
                horizontalLineToRelative(5.0f)
                lineTo(15.5f, 4.0f)
                horizontalLineToRelative(-13.0f)
                close()
                moveTo(21.5f, 9.0f)
                horizontalLineToRelative(-9.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(7.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(-7.0f)
                horizontalLineToRelative(3.0f)
                lineTo(21.5f, 9.0f)
                close()
            }
        }
    }

    val Mic: ImageVector by lazy {
        materialIcon(name = "Glyphs.Mic") {
            materialPath {
                moveTo(12.0f, 14.0f)
                curveToRelative(1.66f, 0.0f, 3.0f, -1.34f, 3.0f, -3.0f)
                verticalLineTo(5.0f)
                curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                reflectiveCurveTo(9.0f, 3.34f, 9.0f, 5.0f)
                verticalLineToRelative(6.0f)
                curveTo(9.0f, 12.66f, 10.34f, 14.0f, 12.0f, 14.0f)
                close()
            }
            materialPath {
                moveTo(17.0f, 11.0f)
                curveToRelative(0.0f, 2.76f, -2.24f, 5.0f, -5.0f, 5.0f)
                reflectiveCurveToRelative(-5.0f, -2.24f, -5.0f, -5.0f)
                horizontalLineTo(5.0f)
                curveToRelative(0.0f, 3.53f, 2.61f, 6.43f, 6.0f, 6.92f)
                verticalLineTo(21.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-3.08f)
                curveToRelative(3.39f, -0.49f, 6.0f, -3.39f, 6.0f, -6.92f)
                horizontalLineTo(17.0f)
                close()
            }
        }
    }

    val ContentCopy: ImageVector by lazy {
        materialIcon(name = "Glyphs.ContentCopy") {
            materialPath {
                moveTo(16.0f, 1.0f)
                lineTo(4.0f, 1.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                horizontalLineToRelative(2.0f)
                lineTo(4.0f, 3.0f)
                horizontalLineToRelative(12.0f)
                lineTo(16.0f, 1.0f)
                close()
                moveTo(19.0f, 5.0f)
                lineTo(8.0f, 5.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(11.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(21.0f, 7.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(19.0f, 21.0f)
                lineTo(8.0f, 21.0f)
                lineTo(8.0f, 7.0f)
                horizontalLineToRelative(11.0f)
                verticalLineToRelative(14.0f)
                close()
            }
        }
    }

    val Gesture: ImageVector by lazy {
        materialIcon(name = "Glyphs.Gesture") {
            materialPath {
                moveTo(4.59f, 6.89f)
                curveToRelative(0.7f, -0.71f, 1.4f, -1.35f, 1.71f, -1.22f)
                curveToRelative(0.5f, 0.2f, 0.0f, 1.03f, -0.3f, 1.52f)
                curveToRelative(-0.25f, 0.42f, -2.86f, 3.89f, -2.86f, 6.31f)
                curveToRelative(0.0f, 1.28f, 0.48f, 2.34f, 1.34f, 2.98f)
                curveToRelative(0.75f, 0.56f, 1.74f, 0.73f, 2.64f, 0.46f)
                curveToRelative(1.07f, -0.31f, 1.95f, -1.4f, 3.06f, -2.77f)
                curveToRelative(1.21f, -1.49f, 2.83f, -3.44f, 4.08f, -3.44f)
                curveToRelative(1.63f, 0.0f, 1.65f, 1.01f, 1.76f, 1.79f)
                curveToRelative(-3.78f, 0.64f, -5.38f, 3.67f, -5.38f, 5.37f)
                curveToRelative(0.0f, 1.7f, 1.44f, 3.09f, 3.21f, 3.09f)
                curveToRelative(1.63f, 0.0f, 4.29f, -1.33f, 4.69f, -6.1f)
                lineTo(21.0f, 14.88f)
                verticalLineToRelative(-2.5f)
                horizontalLineToRelative(-2.47f)
                curveToRelative(-0.15f, -1.65f, -1.09f, -4.2f, -4.03f, -4.2f)
                curveToRelative(-2.25f, 0.0f, -4.18f, 1.91f, -4.94f, 2.84f)
                curveToRelative(-0.58f, 0.73f, -2.06f, 2.48f, -2.29f, 2.72f)
                curveToRelative(-0.25f, 0.3f, -0.68f, 0.84f, -1.11f, 0.84f)
                curveToRelative(-0.45f, 0.0f, -0.72f, -0.83f, -0.36f, -1.92f)
                curveToRelative(0.35f, -1.09f, 1.4f, -2.86f, 1.85f, -3.52f)
                curveToRelative(0.78f, -1.14f, 1.3f, -1.92f, 1.3f, -3.28f)
                curveTo(8.95f, 3.69f, 7.31f, 3.0f, 6.44f, 3.0f)
                curveTo(5.12f, 3.0f, 3.97f, 4.0f, 3.72f, 4.25f)
                curveToRelative(-0.36f, 0.36f, -0.66f, 0.66f, -0.88f, 0.93f)
                lineToRelative(1.75f, 1.71f)
                close()
                moveTo(13.88f, 18.55f)
                curveToRelative(-0.31f, 0.0f, -0.74f, -0.26f, -0.74f, -0.72f)
                curveToRelative(0.0f, -0.6f, 0.73f, -2.2f, 2.87f, -2.76f)
                curveToRelative(-0.3f, 2.69f, -1.43f, 3.48f, -2.13f, 3.48f)
                close()
            }
        }
    }

    val History: ImageVector by lazy {
        materialIcon(name = "Glyphs.History") {
            materialPath {
                moveTo(13.0f, 3.0f)
                curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
                lineTo(1.0f, 12.0f)
                lineToRelative(3.89f, 3.89f)
                lineToRelative(0.07f, 0.14f)
                lineTo(9.0f, 12.0f)
                lineTo(6.0f, 12.0f)
                curveToRelative(0.0f, -3.87f, 3.13f, -7.0f, 7.0f, -7.0f)
                reflectiveCurveToRelative(7.0f, 3.13f, 7.0f, 7.0f)
                reflectiveCurveToRelative(-3.13f, 7.0f, -7.0f, 7.0f)
                curveToRelative(-1.93f, 0.0f, -3.68f, -0.79f, -4.94f, -2.06f)
                lineToRelative(-1.42f, 1.42f)
                curveTo(8.27f, 19.99f, 10.51f, 21.0f, 13.0f, 21.0f)
                curveToRelative(4.97f, 0.0f, 9.0f, -4.03f, 9.0f, -9.0f)
                reflectiveCurveToRelative(-4.03f, -9.0f, -9.0f, -9.0f)
                close()
                moveTo(12.0f, 8.0f)
                verticalLineToRelative(5.0f)
                lineToRelative(4.25f, 2.52f)
                lineToRelative(0.77f, -1.28f)
                lineToRelative(-3.52f, -2.09f)
                lineTo(13.5f, 8.0f)
                close()
            }
        }
    }

    val Forum: ImageVector by lazy {
        materialIcon(name = "Glyphs.Forum") {
            materialPath {
                moveTo(15.0f, 4.0f)
                verticalLineToRelative(7.0f)
                lineTo(5.17f, 11.0f)
                lineTo(4.0f, 12.17f)
                lineTo(4.0f, 4.0f)
                horizontalLineToRelative(11.0f)
                moveToRelative(1.0f, -2.0f)
                lineTo(3.0f, 2.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(14.0f)
                lineToRelative(4.0f, -4.0f)
                horizontalLineToRelative(10.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                lineTo(17.0f, 3.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                close()
                moveTo(21.0f, 6.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(9.0f)
                lineTo(6.0f, 15.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(11.0f)
                lineToRelative(4.0f, 4.0f)
                lineTo(22.0f, 7.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                close()
            }
        }
    }

    val ManageSearch: ImageVector by lazy {
        materialIcon(name = "Glyphs.ManageSearch") {
            materialPath {
                moveTo(7.0f, 9.0f)
                horizontalLineTo(2.0f)
                verticalLineTo(7.0f)
                horizontalLineToRelative(5.0f)
                verticalLineTo(9.0f)
                close()
                moveTo(7.0f, 12.0f)
                horizontalLineTo(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(5.0f)
                verticalLineTo(12.0f)
                close()
                moveTo(20.59f, 19.0f)
                lineToRelative(-3.83f, -3.83f)
                curveTo(15.96f, 15.69f, 15.02f, 16.0f, 14.0f, 16.0f)
                curveToRelative(-2.76f, 0.0f, -5.0f, -2.24f, -5.0f, -5.0f)
                reflectiveCurveToRelative(2.24f, -5.0f, 5.0f, -5.0f)
                reflectiveCurveToRelative(5.0f, 2.24f, 5.0f, 5.0f)
                curveToRelative(0.0f, 1.02f, -0.31f, 1.96f, -0.83f, 2.75f)
                lineTo(22.0f, 17.59f)
                lineTo(20.59f, 19.0f)
                close()
                moveTo(17.0f, 11.0f)
                curveToRelative(0.0f, -1.65f, -1.35f, -3.0f, -3.0f, -3.0f)
                reflectiveCurveToRelative(-3.0f, 1.35f, -3.0f, 3.0f)
                reflectiveCurveToRelative(1.35f, 3.0f, 3.0f, 3.0f)
                reflectiveCurveTo(17.0f, 12.65f, 17.0f, 11.0f)
                close()
                moveTo(2.0f, 19.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(2.0f)
                verticalLineTo(19.0f)
                close()
            }
        }
    }

    val TravelExplore: ImageVector by lazy {
        materialIcon(name = "Glyphs.TravelExplore") {
            materialPath {
                moveTo(19.3f, 16.9f)
                curveToRelative(0.4f, -0.7f, 0.7f, -1.5f, 0.7f, -2.4f)
                curveToRelative(0.0f, -2.5f, -2.0f, -4.5f, -4.5f, -4.5f)
                reflectiveCurveTo(11.0f, 12.0f, 11.0f, 14.5f)
                reflectiveCurveToRelative(2.0f, 4.5f, 4.5f, 4.5f)
                curveToRelative(0.9f, 0.0f, 1.7f, -0.3f, 2.4f, -0.7f)
                lineToRelative(3.2f, 3.2f)
                lineToRelative(1.4f, -1.4f)
                lineTo(19.3f, 16.9f)
                close()
                moveTo(15.5f, 17.0f)
                curveToRelative(-1.4f, 0.0f, -2.5f, -1.1f, -2.5f, -2.5f)
                reflectiveCurveToRelative(1.1f, -2.5f, 2.5f, -2.5f)
                reflectiveCurveToRelative(2.5f, 1.1f, 2.5f, 2.5f)
                reflectiveCurveTo(16.9f, 17.0f, 15.5f, 17.0f)
                close()
                moveTo(12.0f, 20.0f)
                verticalLineToRelative(2.0f)
                curveTo(6.48f, 22.0f, 2.0f, 17.52f, 2.0f, 12.0f)
                curveTo(2.0f, 6.48f, 6.48f, 2.0f, 12.0f, 2.0f)
                curveToRelative(4.84f, 0.0f, 8.87f, 3.44f, 9.8f, 8.0f)
                horizontalLineToRelative(-2.07f)
                curveToRelative(-0.64f, -2.46f, -2.4f, -4.47f, -4.73f, -5.41f)
                verticalLineTo(5.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                horizontalLineTo(8.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(3.0f)
                horizontalLineTo(9.0f)
                lineToRelative(-4.79f, -4.79f)
                curveTo(4.08f, 10.79f, 4.0f, 11.38f, 4.0f, 12.0f)
                curveTo(4.0f, 16.41f, 7.59f, 20.0f, 12.0f, 20.0f)
                close()
            }
        }
    }

    val FormatQuote: ImageVector by lazy {
        materialIcon(name = "Glyphs.FormatQuote") {
            materialPath {
                moveTo(18.62f, 18.0f)
                horizontalLineToRelative(-5.24f)
                lineToRelative(2.0f, -4.0f)
                lineTo(13.0f, 14.0f)
                lineTo(13.0f, 6.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(7.24f)
                lineTo(18.62f, 18.0f)
                close()
                moveTo(16.62f, 16.0f)
                horizontalLineToRelative(0.76f)
                lineTo(19.0f, 12.76f)
                lineTo(19.0f, 8.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(3.62f)
                lineToRelative(-2.0f, 4.0f)
                close()
                moveTo(8.62f, 18.0f)
                lineTo(3.38f, 18.0f)
                lineToRelative(2.0f, -4.0f)
                lineTo(3.0f, 14.0f)
                lineTo(3.0f, 6.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(7.24f)
                lineTo(8.62f, 18.0f)
                close()
                moveTo(6.62f, 16.0f)
                horizontalLineToRelative(0.76f)
                lineTo(9.0f, 12.76f)
                lineTo(9.0f, 8.0f)
                lineTo(5.0f, 8.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(3.62f)
                lineToRelative(-2.0f, 4.0f)
                close()
            }
        }
    }

    val LibraryBooks: ImageVector by lazy {
        materialIcon(name = "Glyphs.LibraryBooks") {
            materialPath {
                moveTo(4.0f, 6.0f)
                lineTo(2.0f, 6.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                lineTo(4.0f, 20.0f)
                lineTo(4.0f, 6.0f)
                close()
                moveTo(20.0f, 2.0f)
                lineTo(8.0f, 2.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(22.0f, 4.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(20.0f, 16.0f)
                lineTo(8.0f, 16.0f)
                lineTo(8.0f, 4.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(12.0f)
                close()
                moveTo(10.0f, 9.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-8.0f)
                close()
                moveTo(10.0f, 12.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-4.0f)
                close()
                moveTo(10.0f, 6.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-8.0f)
                close()
            }
        }
    }

    val Cloud: ImageVector by lazy {
        materialIcon(name = "Glyphs.Cloud") {
            materialPath {
                moveTo(12.0f, 6.0f)
                curveToRelative(2.62f, 0.0f, 4.88f, 1.86f, 5.39f, 4.43f)
                lineToRelative(0.3f, 1.5f)
                lineToRelative(1.53f, 0.11f)
                curveToRelative(1.56f, 0.1f, 2.78f, 1.41f, 2.78f, 2.96f)
                curveToRelative(0.0f, 1.65f, -1.35f, 3.0f, -3.0f, 3.0f)
                horizontalLineTo(6.0f)
                curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
                curveToRelative(0.0f, -2.05f, 1.53f, -3.76f, 3.56f, -3.97f)
                lineToRelative(1.07f, -0.11f)
                lineToRelative(0.5f, -0.95f)
                curveTo(8.08f, 7.14f, 9.94f, 6.0f, 12.0f, 6.0f)
                moveToRelative(0.0f, -2.0f)
                curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
                curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
                curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                horizontalLineToRelative(13.0f)
                curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
                curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                close()
            }
        }
    }

    val CloudDownload: ImageVector by lazy {
        materialIcon(name = "Glyphs.CloudDownload") {
            materialPath {
                moveTo(19.35f, 10.04f)
                curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
                curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
                curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                horizontalLineToRelative(13.0f)
                curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
                curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                close()
                moveTo(19.0f, 18.0f)
                lineTo(6.0f, 18.0f)
                curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
                curveToRelative(0.0f, -2.05f, 1.53f, -3.76f, 3.56f, -3.97f)
                lineToRelative(1.07f, -0.11f)
                lineToRelative(0.5f, -0.95f)
                curveTo(8.08f, 7.14f, 9.94f, 6.0f, 12.0f, 6.0f)
                curveToRelative(2.62f, 0.0f, 4.88f, 1.86f, 5.39f, 4.43f)
                lineToRelative(0.3f, 1.5f)
                lineToRelative(1.53f, 0.11f)
                curveToRelative(1.56f, 0.1f, 2.78f, 1.41f, 2.78f, 2.96f)
                curveToRelative(0.0f, 1.65f, -1.35f, 3.0f, -3.0f, 3.0f)
                close()
                moveTo(13.45f, 10.0f)
                horizontalLineToRelative(-2.9f)
                verticalLineToRelative(3.0f)
                lineTo(8.0f, 13.0f)
                lineToRelative(4.0f, 4.0f)
                lineToRelative(4.0f, -4.0f)
                horizontalLineToRelative(-2.55f)
                close()
            }
        }
    }

    val CloudUpload: ImageVector by lazy {
        materialIcon(name = "Glyphs.CloudUpload") {
            materialPath {
                moveTo(19.35f, 10.04f)
                curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
                curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
                curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                horizontalLineToRelative(13.0f)
                curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
                curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                close()
                moveTo(19.0f, 18.0f)
                horizontalLineTo(6.0f)
                curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
                curveToRelative(0.0f, -2.05f, 1.53f, -3.76f, 3.56f, -3.97f)
                lineToRelative(1.07f, -0.11f)
                lineToRelative(0.5f, -0.95f)
                curveTo(8.08f, 7.14f, 9.94f, 6.0f, 12.0f, 6.0f)
                curveToRelative(2.62f, 0.0f, 4.88f, 1.86f, 5.39f, 4.43f)
                lineToRelative(0.3f, 1.5f)
                lineToRelative(1.53f, 0.11f)
                curveToRelative(1.56f, 0.1f, 2.78f, 1.41f, 2.78f, 2.96f)
                curveToRelative(0.0f, 1.65f, -1.35f, 3.0f, -3.0f, 3.0f)
                close()
                moveTo(8.0f, 13.0f)
                horizontalLineToRelative(2.55f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(2.9f)
                verticalLineToRelative(-3.0f)
                horizontalLineTo(16.0f)
                lineToRelative(-4.0f, -4.0f)
                close()
            }
        }
    }

    val CloudDone: ImageVector by lazy {
        materialIcon(name = "Glyphs.CloudDone") {
            materialPath {
                moveTo(19.35f, 10.04f)
                curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
                curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
                curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                horizontalLineToRelative(13.0f)
                curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
                curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                close()
                moveTo(19.0f, 18.0f)
                lineTo(6.0f, 18.0f)
                curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
                curveToRelative(0.0f, -2.05f, 1.53f, -3.76f, 3.56f, -3.97f)
                lineToRelative(1.07f, -0.11f)
                lineToRelative(0.5f, -0.95f)
                curveTo(8.08f, 7.14f, 9.94f, 6.0f, 12.0f, 6.0f)
                curveToRelative(2.62f, 0.0f, 4.88f, 1.86f, 5.39f, 4.43f)
                lineToRelative(0.3f, 1.5f)
                lineToRelative(1.53f, 0.11f)
                curveToRelative(1.56f, 0.1f, 2.78f, 1.41f, 2.78f, 2.96f)
                curveToRelative(0.0f, 1.65f, -1.35f, 3.0f, -3.0f, 3.0f)
                close()
                moveTo(10.0f, 14.18f)
                lineToRelative(-2.09f, -2.09f)
                lineTo(6.5f, 13.5f)
                lineTo(10.0f, 17.0f)
                lineToRelative(6.01f, -6.01f)
                lineToRelative(-1.41f, -1.41f)
                close()
            }
        }
    }

    val Tune: ImageVector by lazy {
        materialIcon(name = "Glyphs.Tune") {
            materialPath {
                moveTo(3.0f, 17.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(-2.0f)
                lineTo(3.0f, 17.0f)
                close()
                moveTo(3.0f, 5.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(10.0f)
                lineTo(13.0f, 5.0f)
                lineTo(3.0f, 5.0f)
                close()
                moveTo(13.0f, 21.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-8.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(2.0f)
                close()
                moveTo(7.0f, 9.0f)
                verticalLineToRelative(2.0f)
                lineTo(3.0f, 11.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                lineTo(9.0f, 9.0f)
                lineTo(7.0f, 9.0f)
                close()
                moveTo(21.0f, 13.0f)
                verticalLineToRelative(-2.0f)
                lineTo(11.0f, 11.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(10.0f)
                close()
                moveTo(15.0f, 9.0f)
                horizontalLineToRelative(2.0f)
                lineTo(17.0f, 7.0f)
                horizontalLineToRelative(4.0f)
                lineTo(21.0f, 5.0f)
                horizontalLineToRelative(-4.0f)
                lineTo(17.0f, 3.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(6.0f)
                close()
            }
        }
    }

    val VolumeUp: ImageVector by lazy {
        materialIcon(name = "Glyphs.VolumeUp") {
            materialPath {
                moveTo(3.0f, 9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(5.0f, 5.0f)
                lineTo(12.0f, 4.0f)
                lineTo(7.0f, 9.0f)
                lineTo(3.0f, 9.0f)
                close()
                moveTo(10.0f, 8.83f)
                verticalLineToRelative(6.34f)
                lineTo(7.83f, 13.0f)
                lineTo(5.0f, 13.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.83f)
                lineTo(10.0f, 8.83f)
                close()
                moveTo(16.5f, 12.0f)
                curveToRelative(0.0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
                verticalLineToRelative(8.05f)
                curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f)
                close()
                moveTo(14.0f, 3.23f)
                verticalLineToRelative(2.06f)
                curveToRelative(2.89f, 0.86f, 5.0f, 3.54f, 5.0f, 6.71f)
                reflectiveCurveToRelative(-2.11f, 5.85f, -5.0f, 6.71f)
                verticalLineToRelative(2.06f)
                curveToRelative(4.01f, -0.91f, 7.0f, -4.49f, 7.0f, -8.77f)
                curveToRelative(0.0f, -4.28f, -2.99f, -7.86f, -7.0f, -8.77f)
                close()
            }
        }
    }

    val StopCircle: ImageVector by lazy {
        materialIcon(name = "Glyphs.StopCircle") {
            materialPath {
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                curveToRelative(0.0f, 5.52f, 4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                curveTo(22.0f, 6.48f, 17.52f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
                reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
                reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
                reflectiveCurveTo(16.42f, 20.0f, 12.0f, 20.0f)
                close()
                moveTo(16.0f, 16.0f)
                horizontalLineTo(8.0f)
                verticalLineTo(8.0f)
                horizontalLineToRelative(8.0f)
                verticalLineTo(16.0f)
                close()
            }
        }
    }

    val Translate: ImageVector by lazy {
        materialIcon(name = "Glyphs.Translate") {
            materialPath {
                moveTo(12.87f, 15.07f)
                lineToRelative(-2.54f, -2.51f)
                lineToRelative(0.03f, -0.03f)
                curveToRelative(1.74f, -1.94f, 2.98f, -4.17f, 3.71f, -6.53f)
                lineTo(17.0f, 6.0f)
                lineTo(17.0f, 4.0f)
                horizontalLineToRelative(-7.0f)
                lineTo(10.0f, 2.0f)
                lineTo(8.0f, 2.0f)
                verticalLineToRelative(2.0f)
                lineTo(1.0f, 4.0f)
                verticalLineToRelative(1.99f)
                horizontalLineToRelative(11.17f)
                curveTo(11.5f, 7.92f, 10.44f, 9.75f, 9.0f, 11.35f)
                curveTo(8.07f, 10.32f, 7.3f, 9.19f, 6.69f, 8.0f)
                horizontalLineToRelative(-2.0f)
                curveToRelative(0.73f, 1.63f, 1.73f, 3.17f, 2.98f, 4.56f)
                lineToRelative(-5.09f, 5.02f)
                lineTo(4.0f, 19.0f)
                lineToRelative(5.0f, -5.0f)
                lineToRelative(3.11f, 3.11f)
                lineToRelative(0.76f, -2.04f)
                close()
                moveTo(18.5f, 10.0f)
                horizontalLineToRelative(-2.0f)
                lineTo(12.0f, 22.0f)
                horizontalLineToRelative(2.0f)
                lineToRelative(1.12f, -3.0f)
                horizontalLineToRelative(4.75f)
                lineTo(21.0f, 22.0f)
                horizontalLineToRelative(2.0f)
                lineToRelative(-4.5f, -12.0f)
                close()
                moveTo(15.88f, 17.0f)
                lineToRelative(1.62f, -4.33f)
                lineTo(19.12f, 17.0f)
                horizontalLineToRelative(-3.24f)
                close()
            }
        }
    }

    val Timeline: ImageVector by lazy {
        materialIcon(name = "Glyphs.Timeline") {
            materialPath {
                moveTo(23.0f, 8.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                curveToRelative(-0.18f, 0.0f, -0.35f, -0.02f, -0.51f, -0.07f)
                lineToRelative(-3.56f, 3.55f)
                curveTo(16.98f, 13.64f, 17.0f, 13.82f, 17.0f, 14.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                reflectiveCurveToRelative(-2.0f, -0.9f, -2.0f, -2.0f)
                curveToRelative(0.0f, -0.18f, 0.02f, -0.36f, 0.07f, -0.52f)
                lineToRelative(-2.55f, -2.55f)
                curveTo(10.36f, 10.98f, 10.18f, 11.0f, 10.0f, 11.0f)
                curveToRelative(-0.18f, 0.0f, -0.36f, -0.02f, -0.52f, -0.07f)
                lineToRelative(-4.55f, 4.56f)
                curveTo(4.98f, 15.65f, 5.0f, 15.82f, 5.0f, 16.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                reflectiveCurveToRelative(-2.0f, -0.9f, -2.0f, -2.0f)
                reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                curveToRelative(0.18f, 0.0f, 0.35f, 0.02f, 0.51f, 0.07f)
                lineToRelative(4.56f, -4.55f)
                curveTo(8.02f, 9.36f, 8.0f, 9.18f, 8.0f, 9.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                curveToRelative(0.0f, 0.18f, -0.02f, 0.36f, -0.07f, 0.52f)
                lineToRelative(2.55f, 2.55f)
                curveTo(14.64f, 12.02f, 14.82f, 12.0f, 15.0f, 12.0f)
                curveToRelative(0.18f, 0.0f, 0.36f, 0.02f, 0.52f, 0.07f)
                lineToRelative(3.55f, -3.56f)
                curveTo(19.02f, 8.35f, 19.0f, 8.18f, 19.0f, 8.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveTo(23.0f, 6.9f, 23.0f, 8.0f)
                close()
            }
        }
    }

    val Toc: ImageVector by lazy {
        materialIcon(name = "Glyphs.Toc") {
            materialPath {
                moveTo(3.0f, 9.0f)
                horizontalLineToRelative(14.0f)
                lineTo(17.0f, 7.0f)
                lineTo(3.0f, 7.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(3.0f, 13.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                lineTo(3.0f, 11.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(3.0f, 17.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                lineTo(3.0f, 15.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(19.0f, 17.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(19.0f, 7.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                lineTo(21.0f, 7.0f)
                horizontalLineToRelative(-2.0f)
                close()
                moveTo(19.0f, 13.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
    }

    val Visibility: ImageVector by lazy {
        materialIcon(name = "Glyphs.Visibility") {
            materialPath {
                moveTo(12.0f, 6.0f)
                curveToRelative(3.79f, 0.0f, 7.17f, 2.13f, 8.82f, 5.5f)
                curveTo(19.17f, 14.87f, 15.79f, 17.0f, 12.0f, 17.0f)
                reflectiveCurveToRelative(-7.17f, -2.13f, -8.82f, -5.5f)
                curveTo(4.83f, 8.13f, 8.21f, 6.0f, 12.0f, 6.0f)
                moveToRelative(0.0f, -2.0f)
                curveTo(7.0f, 4.0f, 2.73f, 7.11f, 1.0f, 11.5f)
                curveTo(2.73f, 15.89f, 7.0f, 19.0f, 12.0f, 19.0f)
                reflectiveCurveToRelative(9.27f, -3.11f, 11.0f, -7.5f)
                curveTo(21.27f, 7.11f, 17.0f, 4.0f, 12.0f, 4.0f)
                close()
                moveTo(12.0f, 9.0f)
                curveToRelative(1.38f, 0.0f, 2.5f, 1.12f, 2.5f, 2.5f)
                reflectiveCurveTo(13.38f, 14.0f, 12.0f, 14.0f)
                reflectiveCurveToRelative(-2.5f, -1.12f, -2.5f, -2.5f)
                reflectiveCurveTo(10.62f, 9.0f, 12.0f, 9.0f)
                moveToRelative(0.0f, -2.0f)
                curveToRelative(-2.48f, 0.0f, -4.5f, 2.02f, -4.5f, 4.5f)
                reflectiveCurveTo(9.52f, 16.0f, 12.0f, 16.0f)
                reflectiveCurveToRelative(4.5f, -2.02f, 4.5f, -4.5f)
                reflectiveCurveTo(14.48f, 7.0f, 12.0f, 7.0f)
                close()
            }
        }
    }

    val Psychology: ImageVector by lazy {
        materialIcon(name = "Glyphs.Psychology") {
            materialPath {
                moveTo(15.82f, 7.22f)
                lineToRelative(-1.0f, 0.4f)
                curveToRelative(-0.21f, -0.16f, -0.43f, -0.29f, -0.67f, -0.39f)
                lineTo(14.0f, 6.17f)
                curveTo(13.98f, 6.07f, 13.9f, 6.0f, 13.8f, 6.0f)
                horizontalLineToRelative(-1.6f)
                curveToRelative(-0.1f, 0.0f, -0.18f, 0.07f, -0.19f, 0.17f)
                lineToRelative(-0.15f, 1.06f)
                curveToRelative(-0.24f, 0.1f, -0.47f, 0.23f, -0.67f, 0.39f)
                lineToRelative(-1.0f, -0.4f)
                curveToRelative(-0.09f, -0.03f, -0.2f, 0.0f, -0.24f, 0.09f)
                lineToRelative(-0.8f, 1.38f)
                curveToRelative(-0.05f, 0.09f, -0.03f, 0.2f, 0.05f, 0.26f)
                lineToRelative(0.85f, 0.66f)
                curveTo(10.02f, 9.73f, 10.0f, 9.87f, 10.0f, 10.0f)
                curveToRelative(0.0f, 0.13f, 0.01f, 0.26f, 0.03f, 0.39f)
                lineToRelative(-0.84f, 0.66f)
                curveToRelative(-0.08f, 0.06f, -0.1f, 0.17f, -0.05f, 0.25f)
                lineToRelative(0.8f, 1.39f)
                curveToRelative(0.05f, 0.09f, 0.15f, 0.12f, 0.25f, 0.09f)
                lineToRelative(0.99f, -0.4f)
                curveToRelative(0.21f, 0.16f, 0.43f, 0.29f, 0.68f, 0.39f)
                lineTo(12.0f, 13.83f)
                curveToRelative(0.02f, 0.1f, 0.1f, 0.17f, 0.2f, 0.17f)
                horizontalLineToRelative(1.6f)
                curveToRelative(0.1f, 0.0f, 0.18f, -0.07f, 0.2f, -0.17f)
                lineToRelative(0.15f, -1.06f)
                curveToRelative(0.24f, -0.1f, 0.47f, -0.23f, 0.67f, -0.39f)
                lineToRelative(0.99f, 0.4f)
                curveToRelative(0.09f, 0.04f, 0.2f, 0.0f, 0.24f, -0.09f)
                lineToRelative(0.8f, -1.39f)
                curveToRelative(0.05f, -0.09f, 0.03f, -0.19f, -0.05f, -0.25f)
                lineToRelative(-0.83f, -0.66f)
                curveTo(15.99f, 10.26f, 16.0f, 10.13f, 16.0f, 10.0f)
                curveToRelative(0.0f, -0.14f, -0.01f, -0.27f, -0.03f, -0.39f)
                lineToRelative(0.85f, -0.66f)
                curveToRelative(0.08f, -0.06f, 0.1f, -0.17f, 0.05f, -0.26f)
                lineToRelative(-0.8f, -1.38f)
                curveTo(16.02f, 7.22f, 15.91f, 7.19f, 15.82f, 7.22f)
                close()
                moveTo(13.0f, 11.43f)
                curveToRelative(-0.79f, 0.0f, -1.43f, -0.64f, -1.43f, -1.43f)
                reflectiveCurveTo(12.21f, 8.57f, 13.0f, 8.57f)
                reflectiveCurveToRelative(1.43f, 0.64f, 1.43f, 1.43f)
                reflectiveCurveTo(13.79f, 11.43f, 13.0f, 11.43f)
                close()
            }
            materialPath {
                moveTo(19.94f, 9.06f)
                curveToRelative(-0.43f, -3.27f, -3.23f, -5.86f, -6.53f, -6.05f)
                curveTo(13.27f, 3.0f, 13.14f, 3.0f, 13.0f, 3.0f)
                curveTo(9.47f, 3.0f, 6.57f, 5.61f, 6.08f, 9.0f)
                lineToRelative(-1.93f, 3.48f)
                curveTo(3.74f, 13.14f, 4.22f, 14.0f, 5.0f, 14.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(7.0f)
                verticalLineToRelative(-4.68f)
                curveTo(18.62f, 15.07f, 20.35f, 12.24f, 19.94f, 9.06f)
                close()
                moveTo(14.89f, 14.63f)
                lineTo(14.0f, 15.05f)
                verticalLineTo(19.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineTo(8.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineTo(6.7f)
                lineToRelative(1.33f, -2.33f)
                curveTo(8.21f, 7.06f, 10.35f, 5.0f, 13.0f, 5.0f)
                curveToRelative(2.76f, 0.0f, 5.0f, 2.24f, 5.0f, 5.0f)
                curveTo(18.0f, 12.09f, 16.71f, 13.88f, 14.89f, 14.63f)
                close()
            }
        }
    }

    val Brush: ImageVector by lazy {
        materialIcon(name = "Glyphs.Brush") {
            materialPath {
                moveTo(7.0f, 16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                curveToRelative(-0.17f, 0.0f, -0.33f, -0.02f, -0.5f, -0.05f)
                curveToRelative(0.31f, -0.55f, 0.5f, -1.21f, 0.5f, -1.95f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                moveTo(18.67f, 3.0f)
                curveToRelative(-0.26f, 0.0f, -0.51f, 0.1f, -0.71f, 0.29f)
                lineTo(9.0f, 12.25f)
                lineTo(11.75f, 15.0f)
                lineToRelative(8.96f, -8.96f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0.0f, -1.41f)
                lineToRelative(-1.34f, -1.34f)
                curveToRelative(-0.2f, -0.2f, -0.45f, -0.29f, -0.7f, -0.29f)
                close()
                moveTo(7.0f, 14.0f)
                curveToRelative(-1.66f, 0.0f, -3.0f, 1.34f, -3.0f, 3.0f)
                curveToRelative(0.0f, 1.31f, -1.16f, 2.0f, -2.0f, 2.0f)
                curveToRelative(0.92f, 1.22f, 2.49f, 2.0f, 4.0f, 2.0f)
                curveToRelative(2.21f, 0.0f, 4.0f, -1.79f, 4.0f, -4.0f)
                curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                close()
            }
        }
    }

    val ChildCare: ImageVector by lazy {
        materialIcon(name = "Glyphs.ChildCare") {
            materialPath {
                moveTo(14.5f, 10.5f)
                moveToRelative(-1.25f, 0.0f)
                arcToRelative(1.25f, 1.25f, 0.0f, true, true, 2.5f, 0.0f)
                arcToRelative(1.25f, 1.25f, 0.0f, true, true, -2.5f, 0.0f)
            }
            materialPath {
                moveTo(9.5f, 10.5f)
                moveToRelative(-1.25f, 0.0f)
                arcToRelative(1.25f, 1.25f, 0.0f, true, true, 2.5f, 0.0f)
                arcToRelative(1.25f, 1.25f, 0.0f, true, true, -2.5f, 0.0f)
            }
            materialPath {
                moveTo(22.94f, 11.34f)
                curveToRelative(-0.25f, -1.51f, -1.36f, -2.74f, -2.81f, -3.17f)
                curveToRelative(-0.53f, -1.12f, -1.28f, -2.1f, -2.19f, -2.91f)
                curveTo(16.36f, 3.85f, 14.28f, 3.0f, 12.0f, 3.0f)
                reflectiveCurveToRelative(-4.36f, 0.85f, -5.94f, 2.26f)
                curveToRelative(-0.92f, 0.81f, -1.67f, 1.8f, -2.19f, 2.91f)
                curveToRelative(-1.45f, 0.43f, -2.56f, 1.65f, -2.81f, 3.17f)
                curveToRelative(-0.04f, 0.21f, -0.06f, 0.43f, -0.06f, 0.66f)
                curveToRelative(0.0f, 0.23f, 0.02f, 0.45f, 0.06f, 0.66f)
                curveToRelative(0.25f, 1.51f, 1.36f, 2.74f, 2.81f, 3.17f)
                curveToRelative(0.52f, 1.11f, 1.27f, 2.09f, 2.17f, 2.89f)
                curveTo(7.62f, 20.14f, 9.71f, 21.0f, 12.0f, 21.0f)
                reflectiveCurveToRelative(4.38f, -0.86f, 5.97f, -2.28f)
                curveToRelative(0.9f, -0.8f, 1.65f, -1.79f, 2.17f, -2.89f)
                curveToRelative(1.44f, -0.43f, 2.55f, -1.65f, 2.8f, -3.17f)
                curveToRelative(0.04f, -0.21f, 0.06f, -0.43f, 0.06f, -0.66f)
                curveToRelative(0.0f, -0.23f, -0.02f, -0.45f, -0.06f, -0.66f)
                close()
                moveTo(19.0f, 14.0f)
                curveToRelative(-0.1f, 0.0f, -0.19f, -0.02f, -0.29f, -0.03f)
                curveToRelative(-0.2f, 0.67f, -0.49f, 1.29f, -0.86f, 1.86f)
                curveTo(16.6f, 17.74f, 14.45f, 19.0f, 12.0f, 19.0f)
                reflectiveCurveToRelative(-4.6f, -1.26f, -5.85f, -3.17f)
                curveToRelative(-0.37f, -0.57f, -0.66f, -1.19f, -0.86f, -1.86f)
                curveToRelative(-0.1f, 0.01f, -0.19f, 0.03f, -0.29f, 0.03f)
                curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                curveToRelative(0.1f, 0.0f, 0.19f, 0.02f, 0.29f, 0.03f)
                curveToRelative(0.2f, -0.67f, 0.49f, -1.29f, 0.86f, -1.86f)
                curveTo(7.4f, 6.26f, 9.55f, 5.0f, 12.0f, 5.0f)
                reflectiveCurveToRelative(4.6f, 1.26f, 5.85f, 3.17f)
                curveToRelative(0.37f, 0.57f, 0.66f, 1.19f, 0.86f, 1.86f)
                curveToRelative(0.1f, -0.01f, 0.19f, -0.03f, 0.29f, -0.03f)
                curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
                reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                close()
                moveTo(12.0f, 17.0f)
                curveToRelative(2.01f, 0.0f, 3.74f, -1.23f, 4.5f, -3.0f)
                horizontalLineToRelative(-9.0f)
                curveToRelative(0.76f, 1.77f, 2.49f, 3.0f, 4.5f, 3.0f)
                close()
            }
        }
    }

    val Speed: ImageVector by lazy {
        materialIcon(name = "Glyphs.Speed") {
            materialPath {
                moveTo(20.38f, 8.57f)
                lineToRelative(-1.23f, 1.85f)
                arcToRelative(8.0f, 8.0f, 0.0f, false, true, -0.22f, 7.58f)
                horizontalLineTo(5.07f)
                arcTo(8.0f, 8.0f, 0.0f, false, true, 15.58f, 6.85f)
                lineToRelative(1.85f, -1.23f)
                arcTo(10.0f, 10.0f, 0.0f, false, false, 3.35f, 19.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.72f, 1.0f)
                horizontalLineToRelative(13.85f)
                arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.74f, -1.0f)
                arcToRelative(10.0f, 10.0f, 0.0f, false, false, -0.27f, -10.44f)
                close()
            }
            materialPath {
                moveTo(10.59f, 15.41f)
                arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.83f, 0.0f)
                lineToRelative(5.66f, -8.49f)
                lineToRelative(-8.49f, 5.66f)
                arcToRelative(2.0f, 2.0f, 0.0f, false, false, 0.0f, 2.83f)
                close()
            }
        }
    }

    val FormatSize: ImageVector by lazy {
        materialIcon(name = "Glyphs.FormatSize") {
            materialPath {
                moveTo(9.0f, 4.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(12.0f)
                horizontalLineToRelative(3.0f)
                lineTo(17.0f, 7.0f)
                horizontalLineToRelative(5.0f)
                lineTo(22.0f, 4.0f)
                lineTo(9.0f, 4.0f)
                close()
                moveTo(3.0f, 12.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(7.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(-7.0f)
                horizontalLineToRelative(3.0f)
                lineTo(12.0f, 9.0f)
                lineTo(3.0f, 9.0f)
                verticalLineToRelative(3.0f)
                close()
            }
        }
    }

    val Bedtime: ImageVector by lazy {
        materialIcon(name = "Glyphs.Bedtime") {
            materialPath {
                moveTo(9.27f, 4.49f)
                curveToRelative(-1.63f, 7.54f, 3.75f, 12.41f, 7.66f, 13.8f)
                curveTo(15.54f, 19.38f, 13.81f, 20.0f, 12.0f, 20.0f)
                curveToRelative(-4.41f, 0.0f, -8.0f, -3.59f, -8.0f, -8.0f)
                curveTo(4.0f, 8.55f, 6.2f, 5.6f, 9.27f, 4.49f)
                moveTo(11.99f, 2.01f)
                curveTo(6.4f, 2.01f, 2.0f, 6.54f, 2.0f, 12.0f)
                curveToRelative(0.0f, 5.52f, 4.48f, 10.0f, 10.0f, 10.0f)
                curveToRelative(3.71f, 0.0f, 6.93f, -2.02f, 8.66f, -5.02f)
                curveToRelative(-7.51f, -0.25f, -12.09f, -8.43f, -8.32f, -14.97f)
                curveTo(12.22f, 2.01f, 12.11f, 2.01f, 11.99f, 2.01f)
                lineTo(11.99f, 2.01f)
                close()
            }
        }
    }

    val AutoStories: ImageVector by lazy {
        materialIcon(name = "Glyphs.AutoStories") {
            materialPath {
                moveTo(22.47f, 5.2f)
                curveTo(22.0f, 4.96f, 21.51f, 4.76f, 21.0f, 4.59f)
                verticalLineToRelative(12.03f)
                curveTo(19.86f, 16.21f, 18.69f, 16.0f, 17.5f, 16.0f)
                curveToRelative(-1.9f, 0.0f, -3.78f, 0.54f, -5.5f, 1.58f)
                verticalLineTo(5.48f)
                curveTo(10.38f, 4.55f, 8.51f, 4.0f, 6.5f, 4.0f)
                curveTo(4.71f, 4.0f, 3.02f, 4.44f, 1.53f, 5.2f)
                curveTo(1.2f, 5.36f, 1.0f, 5.71f, 1.0f, 6.08f)
                verticalLineToRelative(12.08f)
                curveToRelative(0.0f, 0.58f, 0.47f, 0.99f, 1.0f, 0.99f)
                curveToRelative(0.16f, 0.0f, 0.32f, -0.04f, 0.48f, -0.12f)
                curveTo(3.69f, 18.4f, 5.05f, 18.0f, 6.5f, 18.0f)
                curveToRelative(2.07f, 0.0f, 3.98f, 0.82f, 5.5f, 2.0f)
                curveToRelative(1.52f, -1.18f, 3.43f, -2.0f, 5.5f, -2.0f)
                curveToRelative(1.45f, 0.0f, 2.81f, 0.4f, 4.02f, 1.04f)
                curveToRelative(0.16f, 0.08f, 0.32f, 0.12f, 0.48f, 0.12f)
                curveToRelative(0.52f, 0.0f, 1.0f, -0.41f, 1.0f, -0.99f)
                verticalLineTo(6.08f)
                curveTo(23.0f, 5.71f, 22.8f, 5.36f, 22.47f, 5.2f)
                close()
                moveTo(10.0f, 16.62f)
                curveTo(8.86f, 16.21f, 7.69f, 16.0f, 6.5f, 16.0f)
                curveToRelative(-1.19f, 0.0f, -2.36f, 0.21f, -3.5f, 0.62f)
                verticalLineTo(6.71f)
                curveTo(4.11f, 6.24f, 5.28f, 6.0f, 6.5f, 6.0f)
                curveTo(7.7f, 6.0f, 8.89f, 6.25f, 10.0f, 6.72f)
                verticalLineTo(16.62f)
                close()
                moveTo(19.0f, 0.5f)
                lineToRelative(-5.0f, 5.0f)
                verticalLineTo(15.0f)
                lineToRelative(5.0f, -4.5f)
                verticalLineTo(0.5f)
                close()
            }
        }
    }

    val Lightbulb: ImageVector by lazy {
        materialIcon(name = "Glyphs.Lightbulb") {
            materialPath {
                moveTo(9.0f, 21.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(4.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-1.0f)
                lineTo(9.0f, 20.0f)
                verticalLineToRelative(1.0f)
                close()
                moveTo(12.0f, 2.0f)
                curveTo(8.14f, 2.0f, 5.0f, 5.14f, 5.0f, 9.0f)
                curveToRelative(0.0f, 2.38f, 1.19f, 4.47f, 3.0f, 5.74f)
                lineTo(8.0f, 17.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(6.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-2.26f)
                curveToRelative(1.81f, -1.27f, 3.0f, -3.36f, 3.0f, -5.74f)
                curveToRelative(0.0f, -3.86f, -3.14f, -7.0f, -7.0f, -7.0f)
                close()
                moveTo(14.85f, 13.1f)
                lineToRelative(-0.85f, 0.6f)
                lineTo(14.0f, 16.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(-2.3f)
                lineToRelative(-0.85f, -0.6f)
                curveTo(7.8f, 12.16f, 7.0f, 10.63f, 7.0f, 9.0f)
                curveToRelative(0.0f, -2.76f, 2.24f, -5.0f, 5.0f, -5.0f)
                reflectiveCurveToRelative(5.0f, 2.24f, 5.0f, 5.0f)
                curveToRelative(0.0f, 1.63f, -0.8f, 3.16f, -2.15f, 4.1f)
                close()
            }
        }
    }

    val QuestionAnswer: ImageVector by lazy {
        materialIcon(name = "Glyphs.QuestionAnswer") {
            materialPath {
                moveTo(15.0f, 4.0f)
                verticalLineToRelative(7.0f)
                lineTo(5.17f, 11.0f)
                lineToRelative(-0.59f, 0.59f)
                lineToRelative(-0.58f, 0.58f)
                lineTo(4.0f, 4.0f)
                horizontalLineToRelative(11.0f)
                moveToRelative(1.0f, -2.0f)
                lineTo(3.0f, 2.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(14.0f)
                lineToRelative(4.0f, -4.0f)
                horizontalLineToRelative(10.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                lineTo(17.0f, 3.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                close()
                moveTo(21.0f, 6.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(9.0f)
                lineTo(6.0f, 15.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(11.0f)
                lineToRelative(4.0f, 4.0f)
                lineTo(22.0f, 7.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                close()
            }
        }
    }

    val PhotoLibrary: ImageVector by lazy {
        materialIcon(name = "Glyphs.PhotoLibrary") {
            materialPath {
                moveTo(20.0f, 4.0f)
                verticalLineToRelative(12.0f)
                lineTo(8.0f, 16.0f)
                lineTo(8.0f, 4.0f)
                horizontalLineToRelative(12.0f)
                moveToRelative(0.0f, -2.0f)
                lineTo(8.0f, 2.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(22.0f, 4.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(11.5f, 11.67f)
                lineToRelative(1.69f, 2.26f)
                lineToRelative(2.48f, -3.1f)
                lineTo(19.0f, 15.0f)
                lineTo(9.0f, 15.0f)
                close()
                moveTo(2.0f, 6.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                lineTo(4.0f, 20.0f)
                lineTo(4.0f, 6.0f)
                lineTo(2.0f, 6.0f)
                close()
            }
        }
    }

    val Replay: ImageVector by lazy {
        materialIcon(name = "Glyphs.Replay") {
            materialPath {
                moveTo(12.0f, 5.0f)
                verticalLineTo(1.0f)
                lineTo(7.0f, 6.0f)
                lineToRelative(5.0f, 5.0f)
                verticalLineTo(7.0f)
                curveToRelative(3.31f, 0.0f, 6.0f, 2.69f, 6.0f, 6.0f)
                reflectiveCurveToRelative(-2.69f, 6.0f, -6.0f, 6.0f)
                reflectiveCurveToRelative(-6.0f, -2.69f, -6.0f, -6.0f)
                horizontalLineTo(4.0f)
                curveToRelative(0.0f, 4.42f, 3.58f, 8.0f, 8.0f, 8.0f)
                reflectiveCurveToRelative(8.0f, -3.58f, 8.0f, -8.0f)
                reflectiveCurveTo(16.42f, 5.0f, 12.0f, 5.0f)
                close()
            }
        }
    }

    val Bookmark: ImageVector by lazy {
        materialIcon(name = "Glyphs.Bookmark") {
            materialPath {
                moveTo(17.0f, 3.0f)
                horizontalLineTo(7.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(16.0f)
                lineToRelative(7.0f, -3.0f)
                lineToRelative(7.0f, 3.0f)
                verticalLineTo(5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        materialIcon(name = "Glyphs.ChevronRight") {
            materialPath {
                moveTo(10.0f, 6.0f)
                lineTo(8.59f, 7.41f)
                lineTo(13.17f, 12.0f)
                lineToRelative(-4.58f, 4.59f)
                lineTo(10.0f, 18.0f)
                lineToRelative(6.0f, -6.0f)
                lineToRelative(-6.0f, -6.0f)
                close()
            }
        }
    }

    val LockOpen: ImageVector by lazy {
        materialIcon(name = "Glyphs.LockOpen") {
            materialPath {
                moveTo(18.0f, 8.0f)
                horizontalLineToRelative(-1.0f)
                lineTo(17.0f, 6.0f)
                curveToRelative(0.0f, -2.76f, -2.24f, -5.0f, -5.0f, -5.0f)
                reflectiveCurveTo(7.0f, 3.24f, 7.0f, 6.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(0.0f, -1.66f, 1.34f, -3.0f, 3.0f, -3.0f)
                reflectiveCurveToRelative(3.0f, 1.34f, 3.0f, 3.0f)
                verticalLineToRelative(2.0f)
                lineTo(6.0f, 8.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(10.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(20.0f, 10.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(18.0f, 20.0f)
                lineTo(6.0f, 20.0f)
                lineTo(6.0f, 10.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(10.0f)
                close()
                moveTo(12.0f, 17.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                reflectiveCurveToRelative(-2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                close()
            }
        }
    }

    val Notes: ImageVector by lazy {
        materialIcon(name = "Glyphs.Notes") {
            materialPath {
                moveTo(21.0f, 11.01f)
                lineTo(3.0f, 11.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(18.0f)
                close()
                moveTo(3.0f, 16.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(3.0f)
                close()
                moveTo(21.0f, 6.0f)
                horizontalLineTo(3.0f)
                verticalLineToRelative(2.01f)
                lineTo(21.0f, 8.0f)
                close()
            }
        }
    }
}
