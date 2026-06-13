#!/bin/bash
# Comprehensive provider test runner
# Tests: main page sections, items/thumbnails, search, video details, video sources
set -e

cd /tmp/opencode/testrunner

# First, let's try direct curl tests for each provider to check if sites are reachable
echo "=== Step 1: Site Reachability Check ==="
echo ""

test_site() {
    local name=$1
    local url=$2
    local use_proxy=$3
    
    if [ "$use_proxy" = "proxy" ]; then
        local proxy_url="https://simple-proxy.mda2233.workers.dev/?destination=$(echo -n "$url" | jq -sRr @uri)"
        local code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 "$proxy_url" 2>/dev/null || echo "000")
    else
        local code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 \
            -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" \
            "$url" 2>/dev/null || echo "000")
    fi
    
    if [ "$code" = "200" ]; then
        echo "  ✅ $name ($url) -> HTTP $code"
    elif [ "$code" = "000" ]; then
        echo "  ⏰ $name ($url) -> TIMEOUT"
    elif [ "$code" = "403" ]; then
        echo "  🔒 $name ($url) -> HTTP $code (Cloudflare/blocked)"
    else
        echo "  ❌ $name ($url) -> HTTP $code"
    fi
}

echo "Direct fetch tests:"
test_site "Analdin" "https://www.analdin.com"
test_site "Bingato" "https://bingato.com"
test_site "Blowjobs" "https://blowjobs.pro"
test_site "Freeuseporn" "https://www.freeuseporn.com"
test_site "Fullvideos" "https://www.fullvideos.xxx"
test_site "Hardsexvids" "https://hardsexvids.com"
test_site "Hdporn" "https://www.hdporn.gg"
test_site "Javbangers" "https://www.javbangers.com"
test_site "Laidhub" "https://www.laidhub.com"
test_site "Neporn" "https://neporn.com"
test_site "Pornhat" "https://www.pornhat.com"
test_site "Sexu" "https://sexu.com"
test_site "Shameless" "https://shameless.com"
test_site "Shyfap" "https://www.shyfap.net"
test_site "Spankbang" "https://spankbang.com"
test_site "Taboodude" "https://www.taboodude.com"
test_site "Theyarehuge" "https://www.theyarehuge.com"
test_site "Xasiat" "https://www.xasiat.com"
test_site "Xxxtube" "https://x-x-x.tube"
test_site "Yespornvip" "https://yesporn.vip"

echo ""
echo "Via proxy tests:"
test_site "Blowjobs" "https://blowjobs.pro" "proxy"
test_site "Freeuseporn" "https://www.freeuseporn.com" "proxy"
test_site "Shameless" "https://shameless.com" "proxy"
test_site "Spankbang" "https://spankbang.com" "proxy"

echo ""
echo "=== Step 2: Puppeteer tests for Cloudflare-protected sites ==="
echo ""

for site in "https://blowjobs.pro" "https://x-x-x.tube" "https://yesporn.vip" "https://shameless.com" "https://spankbang.com" "https://www.freeuseporn.com"; do
    name=$(echo "$site" | sed 's|https://\(www\.\)\?||' | sed 's|\.com.*||' | sed 's|\.tube||' | sed 's|\.vip||' | sed 's|\.pro||')
    echo "Testing $name with Puppeteer..."
    timeout 45 node -e "
    const puppeteer = require('/tmp/node_modules/puppeteer');
    (async () => {
        try {
            const browser = await puppeteer.launch({
                executablePath: '/usr/bin/chromium',
                args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage']
            });
            const page = await browser.newPage();
            await page.setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36');
            await page.goto('$site', { waitUntil: 'networkidle2', timeout: 30000 });
            const title = await page.title();
            const items = await page.evaluate(() => {
                // Count various item types
                return {
                    title: document.title,
                    itemCount: document.querySelectorAll('div.item, div.video-item, article, div[class*=video], div[class*=item]').length,
                    links: document.querySelectorAll('a[href]').length,
                    images: document.querySelectorAll('img[src]').length
                };
            });
            console.log(JSON.stringify(items));
            await browser.close();
        } catch(e) {
            console.log('ERROR: ' + e.message);
        }
    })();
    " 2>/dev/null || echo "  ❌ Puppeteer failed for $name"
done

echo ""
echo "=== Step 3: Detailed provider analysis ==="
echo ""
echo "Testing listing pages and selectors for each provider..."
echo ""

run_listing_test() {
    local name=$1
    local url=$2
    local item_selector=$3
    local link_selector=$4
    local title_selector=$5
    local poster_selector=$6
    local use_proxy=$7
    
    if [ "$use_proxy" = "proxy" ]; then
        local fetch_url="https://simple-proxy.mda2233.workers.dev/?destination=$(echo -n "$url" | jq -sRr @uri)"
    else
        local fetch_url="$url"
    fi
    
    local html_file=$(mktemp)
    curl -s --max-time 20 \
        -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" \
        "$fetch_url" > "$html_file" 2>/dev/null
    
    local size=$(wc -c < "$html_file")
    if [ "$size" -lt 100 ]; then
        echo "  ❌ $name - Empty/small response ($size bytes)"
        rm -f "$html_file"
        return
    fi
    
    # Parse with pup or hxselect (use basic grep if not available)
    local items=$(grep -co "$item_selector" < "$html_file" 2>/dev/null || echo "0")
    
    # Extract titles using a more practical approach
    local titles=$(grep -oP 'class="[^"]*title[^"]*"[^>]*>[^<]+' "$html_file" | head -5 2>/dev/null)
    local title_count=$(echo "$titles" | grep -c . 2>/dev/null || echo "0")
    
    # Extract poster URLs
    local posters=$(grep -oP 'data-original="[^"]+"' "$html_file" | head -3)
    local poster_count=$(echo "$posters" | grep -c . 2>/dev/null || echo "0")
    if [ "$poster_count" -eq 0 ]; then
        posters=$(grep -oP 'data-src="[^"]+"' "$html_file" | head -3)
        poster_count=$(echo "$posters" | grep -c . 2>/dev/null || echo "0")
    fi
    if [ "$poster_count" -eq 0 ]; then
        posters=$(grep -oP 'src="https?://[^"]+\.(jpg|jpeg|png|webp)' "$html_file" | head -3)
        poster_count=$(echo "$posters" | grep -c . 2>/dev/null || echo "0")
    fi
    
    # Count video links
    local video_links=$(grep -coP 'href="[^"]*/video[^"]*"' "$html_file" 2>/dev/null || echo "0")
    
    # Check for video_url JS pattern
    local video_urls=$(grep -coP "video_url\s*:" "$html_file" 2>/dev/null || echo "0")
    
    # HTML size
    echo "  $name (${size} bytes): items_found=$(($items > 0)) titles=$title_count posters=$poster_count videolinks=$video_links"
    
    # Show sample titles and posters
    if [ "$title_count" -gt 0 ]; then
        echo "    Titles:"
        grep -oP 'class="[^"]*title[^"]*"[^>]*>[^<]+' "$html_file" | head -3 | while read -r line; do
            echo "      - $(echo "$line" | sed 's/class="[^"]*"[^>]*>//' | head -c 80)"
        done
    fi
    
    if [ "$poster_count" -gt 0 ]; then
        echo "    Poster URLs:"
        echo "$posters" | head -3 | while read -r line; do
            echo "      - $(echo "$line" | head -c 100)"
        done
    fi
    
    rm -f "$html_file"
}

# Working providers
echo "--- Working (by user report) ---"
run_listing_test "Analdin" "https://www.analdin.com/latest-updates/" "div.item" "a[href*=/videos/]" "strong.title" "img.lazy-load"
run_listing_test "Bingato" "https://bingato.com" "div.item" "a[href*=/item/]" "strong.title" "img.lazy-load"

echo ""
echo "--- Partial (by user report) ---"
run_listing_test "Fullvideos" "https://www.fullvideos.xxx/latest-updates/" "div.item" "a[href*=/videos/]" "strong.title" "img.lazyload"
run_listing_test "Hardsexvids" "https://hardsexvids.com/latest-updates/" "div.item" "a[href*=/videos/]" "strong.title" "img.lazy-load"
run_listing_test "Hdporn" "https://www.hdporn.gg/latest-updates/" "div.item" "a[href*=/videos/]" "strong.title" "img"
run_listing_test "Laidhub" "https://www.laidhub.com" "div.item-col" "a[href*=/video/]" "span.title" "img"
run_listing_test "Neporn" "https://neporn.com/latest-updates/" "div.item" "a[href*=/video/]" "strong.title" "img.thumb"
run_listing_test "Pornhat" "https://www.pornhat.com" "div.item" "a" "a[title]" "img"
run_listing_test "Sexu" "https://sexu.com" "li.grid__item" "a.item__main" "a.item__title" "img.item__inner"
run_listing_test "Shameless" "https://shameless.com/latest-updates/" "div.item" "a[href*=/videos/]" "a.card-info__text" "img"
run_listing_test "Shyfap" "https://www.shyfap.net" "div.catalog_item" "a[href*=/video/]" "div.media-card_title" "div.media-card_preview"
run_listing_test "Spankbang" "https://spankbang.com/trending_videos/" "div.js-video-item" "a[href*=/video/]" "img[alt]" "img"
run_listing_test "Theyarehuge" "https://www.theyarehuge.com/recent/" "a.item.drclass" "a.item.drclass" "div.video-title" "img.thumb"
run_listing_test "Xasiat" "https://www.xasiat.com/latest-updates/" "div.item" "a[href*=/videos/]" "strong.title" "img.thumb"

echo ""
echo "--- Not Working (by user report) ---"
run_listing_test "Blowjobs" "https://blowjobs.pro/latest-updates/" "div.item" "a[href*=/videos/]" "strong.title" "img.thumb"
run_listing_test "Freeuseporn" "https://www.freeuseporn.com" "div.video-item" "a" "a[title]" "img"
run_listing_test "Taboodude" "https://www.taboodude.com/latest-updates/" "div.item" "a[href*=/video/]" "strong.title" "img"
run_listing_test "Xxxtube" "https://x-x-x.tube/videos/" "div.catalog_item" "a[href*=/video/]" "div.media-card_title" "div.media-card_preview"
run_listing_test "Yespornvip" "https://yesporn.vip/latest-updates/" "article.loop-video" "a[href]" "header.entry-header span" "img"

echo ""
echo "=== Step 4: Video detail page tests ==="
echo ""

test_video_page() {
    local name=$1
    local url=$2
    local use_proxy=$3
    
    if [ "$use_proxy" = "proxy" ]; then
        local fetch_url="https://simple-proxy.mda2233.workers.dev/?destination=$(echo -n "$url" | jq -sRr @uri)"
    else
        local fetch_url="$url"
    fi
    
    local html_file=$(mktemp)
    curl -s --max-time 20 \
        -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" \
        "$fetch_url" > "$html_file" 2>/dev/null
    
    local size=$(wc -c < "$html_file")
    if [ "$size" -lt 100 ]; then
        echo "  ❌ $name - Empty/small response ($size bytes)"
        rm -f "$html_file"
        return
    fi
    
    # Check for og:title
    local og_title=$(grep -oP 'og:title"[^>]*content="([^"]+)"' "$html_file" | head -1 | sed 's/.*content="//;s/"//' | head -c 80)
    local og_image=$(grep -oP 'og:image"[^>]*content="([^"]+)"' "$html_file" | head -1 | sed 's/.*content="//;s/"//' | head -c 120)
    local h1=$(grep -oP '<h1[^>]*>([^<]+)' "$html_file" | head -1 | sed 's/<h1[^>]*>//' | head -c 80)
    local video_src=$(grep -oP '<video[^>]*src="([^"]+)"' "$html_file" | head -1 | sed 's/.*src="//;s/"//' | head -c 120)
    local source_src=$(grep -oP '<source[^>]*src="([^"]+)"' "$html_file" | head -3 | sed 's/.*src="//;s/"//' | head -c 120)
    
    # JS video_url
    local js_video_url=$(grep -oP "video_url\s*:\s*'([^']+)" "$html_file" | head -1 | sed "s/.*:'//" | head -c 100)
    local js_video_alt=$(grep -oP "video_alt_url\s*:\s*'([^']+)" "$html_file" | head -1 | sed "s/.*:'//" | head -c 100)
    
    # JSON-LD contentUrl
    local jsonld_url=$(grep -oP '"contentUrl"\s*:\s*"([^"]+)"' "$html_file" | head -1 | sed 's/.*:"//;s/"//' | head -c 120)
    
    # get_file URLs
    local get_file_urls=$(grep -oP 'https?://[^"'\'' ]+get_file[^"'\'' ]+\.mp4' "$html_file" | head -3)
    local get_file_count=$(echo "$get_file_urls" | grep -c . 2>/dev/null || echo "0")
    
    # description
    local description=$(grep -oP 'name="description"[^>]*content="([^"]+)"' "$html_file" | head -1 | sed 's/.*content="//;s/"//' | head -c 80)
    
    echo "  $name (${size}B)"
    echo "    Title: h1='$h1'"$'\n'"          og:title='$og_title'"
    echo "    Poster: og:image='$og_image'"
    if [ -n "$description" ]; then
        echo "    Description: '$description'"
    fi
    if [ -n "$video_src" ]; then
        echo "    Video[src]: '$video_src'"
    fi
    if [ -n "$source_src" ]; then
        echo "    Source[src]: '$source_src'"
    fi
    if [ -n "$js_video_url" ]; then
        echo "    JS video_url: '$js_video_url'"
    fi
    if [ -n "$js_video_alt" ]; then
        echo "    JS video_alt_url: '$js_video_alt'"
    fi
    if [ -n "$jsonld_url" ]; then
        echo "    JSON-LD contentUrl: '$jsonld_url'"
    fi
    if [ "$get_file_count" -gt 0 ]; then
        echo "    get_file URLs ($get_file_count found):"
        echo "$get_file_urls" | while read -r line; do
            echo "      - $line"
        done
    fi
    
    # Check for video_url JS variable
    local has_video_url_js=$(grep -c "video_url" "$html_file" 2>/dev/null || echo "0")
    echo "    video_url JS vars: $has_video_url_js"
    
    rm -f "$html_file"
}

echo "Testing video detail pages (picking a sample video from each provider)"
echo "--- Working ---"
test_video_page "Analdin" "https://www.analdin.com/latest-updates/"
# Get a specific video URL from Bingato
test_video_page "Bingato" "https://bingato.com"

echo ""
echo "--- Partial ---"
test_video_page "Fullvideos" "https://www.fullvideos.xxx/latest-updates/"
test_video_page "Hardsexvids" "https://hardsexvids.com/latest-updates/"
test_video_page "Hdporn" "https://www.hdporn.gg/latest-updates/"
test_video_page "Laidhub" "https://www.laidhub.com"
test_video_page "Neporn" "https://neporn.com/latest-updates/"
test_video_page "Pornhat" "https://www.pornhat.com"
test_video_page "Sexu" "https://sexu.com"
test_video_page "Shameless" "https://shameless.com/latest-updates/"
test_video_page "Shyfap" "https://www.shyfap.net"
test_video_page "Spankbang" "https://spankbang.com/trending_videos/"
test_video_page "Theyarehuge" "https://www.theyarehuge.com/recent/"
test_video_page "Xasiat" "https://www.xasiat.com/latest-updates/"

echo ""
echo "--- Not Working ---"
test_video_page "Blowjobs" "https://blowjobs.pro/latest-updates/"
test_video_page "Taboodude" "https://www.taboodude.com/latest-updates/"
test_video_page "Xxxtube" "https://x-x-x.tube/videos/"
test_video_page "Yespornvip" "https://yesporn.vip/latest-updates/"

echo ""
echo "=== Step 5: Video source extraction tests ==="
echo ""
echo "Testing video source extraction from specific video pages..."

# Now find actual video URLs from listing pages and test them
extract_and_test() {
    local name=$1
    local url=$2
    local link_pattern=$3
    local use_proxy=$4
    
    local proxy_flag=""
    if [ "$use_proxy" = "proxy" ]; then
        proxy_flag="proxy"
    fi
    
    # First fetch the listing page
    if [ "$use_proxy" = "proxy" ]; then
        local fetch_url="https://simple-proxy.mda2233.workers.dev/?destination=$(echo -n "$url" | jq -sRr @uri)"
    else
        local fetch_url="$url"
    fi
    
    local html_file=$(mktemp)
    curl -s --max-time 20 \
        -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" \
        "$fetch_url" > "$html_file" 2>/dev/null
    
    local size=$(wc -c < "$html_file")
    if [ "$size" -lt 100 ]; then
        echo "  ❌ $name - Cannot fetch listing page"
        rm -f "$html_file"
        return
    fi
    
    # Extract video links
    local video_links=$(grep -oP "href=\"([^\"]*$link_pattern[^\"]*)\"" "$html_file" | head -5 | sed 's/href="//;s/"//')
    local link_count=$(echo "$video_links" | grep -c . 2>/dev/null || echo "0")
    
    if [ "$link_count" -eq 0 ]; then
        # Try alternative patterns
        video_links=$(grep -oP 'href="([^"]*/video[^"]*)"' "$html_file" | head -5 | sed 's/href="//;s/"//')
        link_count=$(echo "$video_links" | grep -c . 2>/dev/null || echo "0")
    fi
    
    if [ "$link_count" -eq 0 ]; then
        echo "  ❌ $name - No video links found on $url"
        rm -f "$html_file"
        return
    fi
    
    # Make URLs absolute
    local base=$(echo "$url" | grep -oP 'https?://[^/]+')
    local first_link=$(echo "$video_links" | head -1)
    if echo "$first_link" | grep -q "^http"; then
        local video_url="$first_link"
    elif echo "$first_link" | grep -q "^//"; then
        local video_url="https:$first_link"
    else
        local video_url="${base}${first_link}"
    fi
    
    echo "  $name - Testing video page: $video_url"
    
    # Fetch video page
    if [ "$use_proxy" = "proxy" ]; then
        local vfetch_url="https://simple-proxy.mda2233.workers.dev/?destination=$(echo -n "$video_url" | jq -sRr @uri)"
    else
        local vfetch_url="$video_url"
    fi
    
    local vhtml_file=$(mktemp)
    curl -s --max-time 20 \
        -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" \
        -H "Referer: $video_url" \
        "$vfetch_url" > "$vhtml_file" 2>/dev/null
    
    local vsize=$(wc -c < "$vhtml_file")
    if [ "$vsize" -lt 100 ]; then
        echo "    ❌ Cannot fetch video page"
        rm -f "$html_file" "$vhtml_file"
        return
    fi
    
    echo "    Page size: ${vsize}B"
    
    # Test strategies
    # Strategy 1: video_url JS regex
    local js_urls=$(grep -oP "video_url\s*:\s*'([^']+)" "$vhtml_file" | sed "s/.*:'//" | head -3)
    local js_count=$(echo "$js_urls" | grep -c . 2>/dev/null || echo "0")
    echo "    Strategy 1 (video_url JS): $js_count found"
    if [ "$js_count" -gt 0 ]; then
        echo "$js_urls" | while read -r line; do
            echo "      - $(echo "$line" | head -c 120)"
        done
    fi
    
    # Strategy 2: video source[src]
    local src_urls=$(grep -oP '<source[^>]*src="([^"]+)"' "$vhtml_file" | sed 's/.*src="//;s/"//' | head -5)
    local src_count=$(echo "$src_urls" | grep -c . 2>/dev/null || echo "0")
    echo "    Strategy 2 (video source[src]): $src_count found"
    if [ "$src_count" -gt 0 ]; then
        echo "$src_urls" | while read -r line; do
            # Check for quality label
            local label=$(grep -oP "label=\"([^\"]+)\"" <<< "$line" 2>/dev/null || echo "")
            echo "      - $(echo "$line" | head -c 120)"
        done
    fi
    
    # Strategy 3: get_file regex
    local get_file_urls=$(grep -oP 'https?://[^"'"'"' ]+get_file[^"'"'"' ]+\.mp4' "$vhtml_file" | head -5)
    local get_file_count=$(echo "$get_file_urls" | grep -c . 2>/dev/null || echo "0")
    echo "    Strategy 3 (get_file regex): $get_file_count found"
    if [ "$get_file_count" -gt 0 ]; then
        echo "$get_file_urls" | while read -r line; do
            echo "      - $(echo "$line" | head -c 120)"
        done
    fi
    
    # Strategy 4: bkcdn/bxcdn regex
    local cdn_urls=$(grep -oP 'https?://[^"'"'"' ]+(bkcdn|bxcdn)[^"'"'"' ]+\.mp4' "$vhtml_file" | head -5)
    local cdn_count=$(echo "$cdn_urls" | grep -c . 2>/dev/null || echo "0")
    echo "    Strategy 4 (bkcdn/bxcdn): $cdn_count found"
    
    # Strategy 5: contentUrl (JSON-LD)
    local content_url=$(grep -oP '"contentUrl"\s*:\s*"([^"]+)"' "$vhtml_file" | head -1 | sed 's/.*:"//;s/"//')
    if [ -n "$content_url" ]; then
        echo "    Strategy 5 (JSON-LD contentUrl): $(echo "$content_url" | head -c 120)"
    fi
    
    # Check video[src]
    local video_src=$(grep -oP '<video[^>]*src="([^"]+)"' "$vhtml_file" | head -3 | sed 's/.*src="//;s/"//')
    local video_src_count=$(echo "$video_src" | grep -c . 2>/dev/null || echo "0")
    if [ "$video_src_count" -gt 0 ]; then
        echo "    Video[src] element: $video_src_count found"
        echo "$video_src" | while read -r line; do
            echo "      - $(echo "$line" | head -c 120)"
        done
    fi
    
    # Quality labels for source elements
    local labels=$(grep -oP 'label="([^"]+)"' "$vhtml_file" | head -5 | sed 's/label="//;s/"//')
    local label_count=$(echo "$labels" | grep -c . 2>/dev/null || echo "0")
    if [ "$label_count" -gt 0 ]; then
        echo "    Quality labels: $(echo "$labels" | tr '\n' ' ')"
    fi
    
    rm -f "$html_file" "$vhtml_file"
}

echo ""
echo "Testing video source extraction..."
for entry in \
    "Analdin|https://www.analdin.com/latest-updates/|/videos/" \
    "Bingato|https://bingato.com|/item/" \
    "Fullvideos|https://www.fullvideos.xxx/latest-updates/|/videos/" \
    "Hardsexvids|https://hardsexvids.com/latest-updates/|/videos/" \
    "Hdporn|https://www.hdporn.gg/latest-updates/|/videos/" \
    "Javbangers|https://www.javbangers.com|/video/" \
    "Laidhub|https://www.laidhub.com|/video/" \
    "Neporn|https://neporn.com/latest-updates/|/video/" \
    "Pornhat|https://www.pornhat.com|/video/" \
    "Sexu|https://sexu.com|/video/" \
    "Shameless|https://shameless.com/latest-updates/|/videos/" \
    "Shyfap|https://www.shyfap.net|/video/" \
    "Spankbang|https://spankbang.com/trending_videos/|/video/" \
    "Taboodude|https://www.taboodude.com/latest-updates/|/video/" \
    "Theyarehuge|https://www.theyarehuge.com/recent/|/video/" \
    "Xasiat|https://www.xasiat.com/latest-updates/|/videos/" \
    "Xxxtube|https://x-x-x.tube/videos/|/video/" \
    "Yespornvip|https://yesporn.vip/latest-updates/|/video/"
do
    IFS='|' read -r name url pattern <<< "$entry"
    echo ""
    extract_and_test "$name" "$url" "$pattern"
done

echo ""
echo "=== All Tests Complete ==="
