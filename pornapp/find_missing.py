#!/usr/bin/env python3
"""Compare API sites with existing Kotlin providers to find candidates for new providers."""

import json, collections, time, subprocess, base64, requests, os

BASE_URL = 'https://porn-app.com/api/v9'
PUBKEY_PATH = '/tmp/pubkey.pem'

def gen_hash():
    ts = int(time.time())
    payload = collections.OrderedDict([
        ('id', 'a1b2c3d4e5f6a7b8'), ('isTV', False),
        ('loginStatus', collections.OrderedDict([('pro', 0), ('status', 0), ('token', ''), ('unixtime', ts), ('user_id', 0)])),
        ('packageName', 'com.streamdev.aiostreamer'),
        ('signatures', ['VQMyUhZdmnnwK5RVCbeGqu0HN020MEDUM44crQyL1zw=']),
        ('time', ts), ('version', 6643)
    ])
    json_str = json.dumps(payload, separators=(',', ':'))
    p1 = subprocess.Popen(['openssl', 'pkeyutl', '-encrypt', '-pubin', '-inkey', PUBKEY_PATH,
                           '-pkeyopt', 'rsa_padding_mode:pkcs1'],
                          stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    enc_out, err = p1.communicate(input=json_str.encode())
    return base64.b64encode(enc_out).decode('utf-8').strip() if p1.returncode == 0 else ''

# Get API sites
headers = {'User-Agent': 'okhttp/5.3.2', 'Accept-Encoding': 'gzip', 'hash': gen_hash()}
resp = requests.get(f'{BASE_URL}/sites', headers=headers, timeout=30)
api_data = resp.json()

# Build API site list with sitetag -> info mapping
api_sites = {}  # sitetag -> {name, category, online, preview, search}
for category, sites in api_data.items():
    for site in sites:
        sitetag = site.get('sitetag', '')
        # Skip placeholders
        if sitetag in ('place', 'HEADER', ''):
            continue
        api_sites[sitetag] = {
            'name': site.get('name', ''),
            'category': category,
            'online': site.get('online', False),
            'preview': site.get('preview', False),
            'search': site.get('search', False),
        }

# Known Kotlin provider directories
known_dirs = set()
for d in os.listdir('/CXXX/CXXX/'):
    if os.path.isdir(os.path.join('/CXXX/CXXX/', d)) and \
       os.path.exists(os.path.join('/CXXX/CXXX/', d, 'build.gradle.kts')) and \
       d not in ('pornapi', 'provider-tester', 'cloudstream-worker', 'gradle', 'build', '.gradle', '.git', '.github'):
        known_dirs.add(d.lower())

# Mapping from sitetag to likely directory name
# These are the ones we already know about
KNOWN_MAPPINGS = {
    'beegcom': 'beeg',
    'shyfapnet': 'shyfap',
    'hardsexvidscom': 'hardsexvids',
    'neporncom': 'neporn',
    'xasiatcom': 'xasiat',
    'xxxtube': 'x-x-x.tube',
    'yespornvip': 'yespornvip',
    'taboodudecom': 'taboodude',
    'pornhubcom': None,  # Could map to HStream or not exist
    'hqpornercom': 'hqporner',
    'xvideoscom': 'xvideos',
    'xhamstercom': 'xhamster',
    'xnxxcom': 'xnxx',
    'pornhatcom': 'pornhat',
    'porntrexcom': 'porntrex',
    'epornercom': 'eporner',
    'javtifulcom': 'javtiful',
    'sexucom': 'sexu',
    'sextbnet': 'sextb',
    'shamelesscom': 'shameless',
    'theyarehugecom': 'theyarehuge',
    'laidhubcom': 'laidhub',
    'pornonecom': 'pornone',
    'pornmzcom': 'pornmz',
    'whoreshubcom': 'whoreshub',
    'spankbangcom': 'spankbang',
    'javbangerscom': 'javbangers',
    'javguru': 'javguru',
    'supjavcom': 'superjav',
    'missavws': 'missav',
    'bingatocom': 'bingato',
    'freeuseporncom': 'freeuseporn',
    'trendyporncom': 'trendyporn',
    'sxyprncom': 'sxyprn',
    'paradisehillcc': 'paradisehill',
    'blowjobspro': 'blowjobs',
    'internetchickscom': 'internetchicks',
    'perverzijacom': 'perverzija',
    'uncutmazacc': 'uncutmaza',
    'analdincom': 'analdin',
    'hentaicloudcom': 'hentaicity',  # guess
    'hentaimamaio': None,
    'hentaiplaynet': None,
    'hentaigasmcom': None,
    'pornslashcom': None,
    'pornvovnet': None,
    'watchpornto': None,
    'xxxfilescom': None,
    'camcamcc': None,
    'javcutvip': None,
    'javflixcc': None,
    'javgigacom': None,
    'javfinderai': None,
    'koreanpornmoviecom': None,
    'tktubecom': None,
    'vjavcom': None,
    '4k69com': None,
    'hdporn92com': None,
    'latestpornvideocom': None,
    'latestleaksco': None,
    'mypornerleakcom': None,
    'porn4dayspw': None,
    'pornohdblue': None,
    'severeporncom': None,
    'pornxphn': None,
    'abxxxcom': None,
    'blowjobspro': None,
    'fpoxxx': None,
    'hclipscom': None,
    'hornyfaptv': None,
    'hornyleaktv': None,
    'hornysimpcom': None,
    'hotscopetv': None,
    'leakpornercom': None,
    'nsfw247to': None,
    'notfanscom': None,
    'pimpbunnycom': None,
    'thotslifecom': None,
    'youjizzcom': None,
    'motherlesscom': None,
    'tnaflixcom': None,
    'tube8com': None,
    'redtubecom': None,
    'youporncom': None,
    'peekvidscom': None,
    'porn00org': None,
    'pornwextv': None,
    'porngocom': None,
    'porndittcom': None,
    'pornohdblue': None,
    'superporncom': None,
    'youcrazyxcom': None,
    'xfreehdcom': None,
    'xtitscom': None,
    'fapnutnet': None,
    'dirtyshipcom': None,
    'pornodddcom': None,
    'porntrycom': None,
    'txxxcom': None,
    'netfapxcom': None,
    'ogporncom': None,
    'yespornpleasexxxcom': None,
    'sexmexxxx': None,
    'reptylecom': None,
    'honeytranscom': None,
    'xxxfreewatch': None,
    'sxylandcom': None,
    'siskavideo': None,
    'porndishcom': None,
    'xmoviesforyoucom': None,
    '0dayxxcom': None,
    'letsjerktv': None,
    'sextvxcom': None,
    'tubepornclassiccom': None,
    'xbayme': None,
    'xozillacom': None,
    'naughtymachinimacom': None,
    'rule34videocom': None,
    'pornmzcom': 'pornmz',
    'brazzerscom': None,  # paysite
    'bangbroscom': None,  # paysite
    'realitykingscom': None,  # paysite
    'mofoscom': None,  # paysite
    'fakehubcom': None,  # paysite
    'bongacamscom': None,
    'chaturbatecom': None,
    'stripchatcom': None,
    'camwhoresbaycom': None,
    'trannyone': None,
    'trannytubetv': None,
    'trannyvideosxcom': None,
    'ashemaletubecom': None,
    'tgtsporncom': None,
    'hypnotubecom': None,
    'boyfriendtv': None,
    'bdsmone': None,
    'boundhubcom': None,
    'fyxxrto': None,
    'familyporntv': None,
    'fulltabootv': None,
    'milfcapscom': None,
    'milfnutcom': None,
    'taboodaddycom': None,
    'taboofantazycom': None,
    'tabootubexxx': None,
    'pornpluscom': None,
    'pornproscom': None,
    'pornddcom': None,
    'porntncom': None,
    'freshpornoorg': None,
    'promooffer': None,
    'paysites': None,
    'fullmoviesxxx': None,
    'hdporngg': 'hdporn.gg',
    'itsporn': None,
    'nsfwswipecom': None,
    'xmegadrivecom': None,
    'pornhubcomtrans': None,
    'pornslashcomshemale': None,
    'redtubecomtrans': None,
    'xhamstercomtrans': None,
    'pornhubcomgay': None,
    'pornslashcomgay': None,
    'porntrexcomgay': None,
    'redtubecomgay': None,
    'tube8comgay': None,
    'xhamstercomgay': None,
    'xvideoscomgay': None,
    'xanimeporncom': None,
    'miohentaicom': None,
    'koreanbjclub': None,
    'pornhubpremiumcom': None,
    'xvideosred': None,
}

# Find which API sitetags don't have a matching Kotlin provider
free_categories = ['premiumporn', 'alternativesites', 'amateur', 'hosters',
                   'camporn', 'familyporn', 'jav', 'hentai', 'other',
                   'extreme', 'trans', 'gay']

missing_free = []
for sitetag, info in sorted(api_sites.items()):
    if info['category'] in ('paysites',):
        continue  # Skip paid sites
    if not info['online']:
        continue  # Skip offline sites

    # Check if this sitetag has a matching Kotlin provider
    matched = False
    direct_match = KNOWN_MAPPINGS.get(sitetag)
    if direct_match and direct_match.lower() in known_dirs:
        matched = True
    elif sitetag.lower() in known_dirs:
        matched = True

    if not matched:
        missing_free.append((sitetag, info))

# Group by category
by_category = {}
for sitetag, info in missing_free:
    cat = info['category']
    if cat not in by_category:
        by_category[cat] = []
    by_category[cat].append((sitetag, info['name'], info['search'], info['preview']))

print(f"=== SITES IN API BUT NOT IN KOTLIN (FREE ONLY, {len(missing_free)} sites) ===\n")

for cat in sorted(by_category.keys()):
    items = by_category[cat]
    print(f"\n{'='*60}")
    print(f"{cat.upper()} ({len(items)} sites)")
    print(f"{'='*60}")
    for sitetag, name, search, preview in sorted(items, key=lambda x: x[1]):
        flags = []
        if search: flags.append('search')
        if preview: flags.append('previews')
        flag_str = ' [' + '|'.join(flags) + ']' if flags else ''
        print(f"  {name:25s} sitetag={sitetag:25s}{flag_str}")
