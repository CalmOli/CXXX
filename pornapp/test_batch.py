#!/usr/bin/env python3
"""Batch test all free API sites in groups of 10."""

import json, collections, time, subprocess, base64, requests, gzip, sys

BASE_URL = 'https://porn-app.com/api/v9'
PUBKEY_PATH = '/tmp/pubkey.pem'
BATCH_SIZE = 10

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

HASH = gen_hash()

def api_post(endpoint, payload):
    url = f'{BASE_URL}{endpoint}'
    headers = {
        'User-Agent': 'okhttp/5.3.2', 'Authorization': 'Bearer ',
        'Content-Type': 'application/json; charset=UTF-8', 'Content-Encoding': 'gzip',
        'hash': HASH
    }
    try:
        resp = requests.post(url, headers=headers, data=gzip.compress(json.dumps(payload).encode()), timeout=30)
        return resp
    except Exception as e:
        return None

def test_lifecycle(sitetag, name):
    result = {'sitetag': sitetag, 'name': name, 'status': 'UNTESTED', 'streams': 0, 'working': 0, 'error': ''}

    # 1. Get site info for base URL
    info_resp = api_post(f'/sites/{sitetag}/info', {'site': sitetag, 'filter': None, 'pornTabs': False, 'globalSearch': False})
    if not info_resp or info_resp.status_code != 200:
        result['status'] = 'INFO_FAIL'
        result['error'] = f'/info returned {info_resp.status_code if info_resp else "no response"}'
        return result

    info = info_resp.json()
    base = info.get('base', '')
    new_url = info.get('newUrl', '')
    if not base:
        result['status'] = 'NO_BASE'
        return result

    # Determine listing URL (use base or first page of newUrl)
    listing_url = new_url.replace('%d', '1') if '%d' in new_url else base
    if listing_url.startswith('/'):
        listing_url = base.rstrip('/') + listing_url

    # 2. Fetch listing page
    try:
        listing_resp = requests.get(listing_url, headers={'User-Agent': 'Mozilla/5.0'}, timeout=20)
        if listing_resp.status_code != 200:
            result['status'] = 'LISTING_404'
            result['error'] = f'HTTP {listing_resp.status_code}'
            return result
        listing_html = listing_resp.text
        if len(listing_html) < 1000:
            result['status'] = 'LISTING_TOO_SMALL'
            result['error'] = f'{len(listing_html)} bytes'
            return result
    except Exception as e:
        result['status'] = 'LISTING_FAIL'
        result['error'] = str(e)[:80]
        return result

    # 3. Call /data
    data_resp = api_post(f'/sites/{sitetag}/data?isTV=false', {'payload': listing_html})
    if not data_resp or data_resp.status_code != 200:
        result['status'] = 'DATA_FAIL'
        result['error'] = f'/data returned {data_resp.status_code if data_resp else "no response"}'
        return result

    try:
        data = data_resp.json()
        if isinstance(data, dict):
            for k in ['videos', 'items', 'data', 'results']:
                if k in data and isinstance(data[k], list):
                    data = data[k]
                    break
            else:
                data = [data]
        videos = [v for v in data if isinstance(v, dict) and v.get('link') != 'NATIVE_AD' and v.get('link')]
    except:
        result['status'] = 'DATA_PARSE_FAIL'
        return result

    if not videos:
        result['status'] = 'NO_VIDEOS'
        return result

    vid = videos[0]
    vid_url = vid.get('link', vid.get('url', ''))
    vid_id = vid.get('video_id', vid.get('id', 0))

    # 4. Fetch video page
    try:
        video_resp = requests.get(vid_url, headers={'User-Agent': 'Mozilla/5.0'}, timeout=20)
        if video_resp.status_code != 200:
            result['status'] = 'VIDEO_404'
            result['error'] = f'HTTP {video_resp.status_code}'
            return result
        video_html = video_resp.text
        if len(video_html) < 1000:
            result['status'] = 'VIDEO_TOO_SMALL'
            return result
    except Exception as e:
        result['status'] = 'VIDEO_FAIL'
        result['error'] = str(e)[:80]
        return result

    # 5. Call /stream
    stream_payload = {
        'payload': video_html,
        'videoObject': {
            'sourceLink': vid_url,
            'site': sitetag
        }
    }
    stream_resp = api_post(f'/sites/{sitetag}/stream?isTV=false', stream_payload)
    if not stream_resp or stream_resp.status_code != 200:
        result['status'] = 'STREAM_FAIL'
        result['error'] = f'/stream returned {stream_resp.status_code if stream_resp else "no response"}: {(stream_resp.text[:100] if stream_resp else "")}'
        return result

    try:
        links = stream_resp.json()
    except:
        result['status'] = 'STREAM_PARSE_FAIL'
        return result

    if not links:
        result['status'] = 'STREAM_EMPTY'
        return result

    # 6. Verify stream URLs
    for link in links:
        url = link.get('stream', link.get('streamLink', ''))
        if url:
            result['streams'] += 1
            try:
                vr = requests.get(url, headers={'User-Agent': 'okhttp/5.3.2'}, stream=True, timeout=10)
                fb = vr.raw.read(16)
                vr.close()
                ct = vr.headers.get('Content-Type', '')
                if vr.status_code == 200 and ('video/' in ct or fb[:3] == b'\x00\x00\x00'):
                    result['working'] += 1
            except:
                pass

    result['status'] = 'PASS' if result['working'] > 0 else 'LINKS_BROKEN'
    return result

def main():
    # Get full site list from API
    headers = {'User-Agent': 'okhttp/5.3.2', 'Accept-Encoding': 'gzip', 'hash': HASH}
    resp = requests.get(f'{BASE_URL}/sites', headers=headers, timeout=30)
    api_data = resp.json()

    # Build free site list (exclude paysites, placeholders, offline)
    free_sites = []
    for category, sites in api_data.items():
        if category == 'paysites':
            continue
        for site in sites:
            sitetag = site.get('sitetag', '')
            if sitetag in ('place', 'HEADER', ''):
                continue
            if not site.get('online', False):
                continue
            free_sites.append((sitetag, site.get('name', '')))

    free_sites.sort(key=lambda x: x[1])
    total = len(free_sites)
    print(f'Total free sites to test: {total}\n')

    # Parse command line for batch to run
    batch_num = 1
    if len(sys.argv) > 1:
        batch_num = int(sys.argv[1])

    start = (batch_num - 1) * BATCH_SIZE
    end = min(start + BATCH_SIZE, total)
    batch = free_sites[start:end]

    if not batch:
        print(f'No sites in batch {batch_num}')
        return

    print(f'Batch {batch_num}: sites {start+1}-{end} of {total}')
    print('=' * 90)

    results = []
    for sitetag, name in batch:
        print(f'\n[{start + len(results) + 1}/{total}] Testing {name} ({sitetag})...')
        sys.stdout.flush()
        r = test_lifecycle(sitetag, name)
        results.append(r)
        status_icon = '✅' if r['status'] == 'PASS' else '❌'
        print(f'  {status_icon} {r["status"]}: {r["streams"]} streams, {r["working"]} working')
        if r['error']:
            print(f'  Error: {r["error"][:120]}')

    print(f'\n{"="*90}')
    print(f'BATCH {batch_num} SUMMARY')
    print(f'{"="*90}')
    passed = sum(1 for r in results if r['status'] == 'PASS')
    for r in results:
        icon = '✅' if r['status'] == 'PASS' else '❌'
        print(f'  {icon} {r["name"]:25s} | {r["status"]:15s} | {r["working"]}/{r["streams"]} streams')
    print(f'\nPassed: {passed}/{len(results)}')

if __name__ == '__main__':
    main()
