import com.lagradost.cloudstream3.gradle.CloudstreamExtension

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

version = 3

cloudstream {
    language = "vi"
    description = "Xem phim cập nhật hàng ngày từ API Nguồn C"
    authors = listOf("Personal")
    status = 1 // 1: Hoạt động tốt
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AsianDrama"
    )
    iconUrl = "https://phim.nguonc.com/public/images/Logo/logonguonc.png"
}
