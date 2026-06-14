#!/bin/bash
TIMESTAMP=$(date +%s)
HASH_JSON=$(python3 -c "
import json, collections
ts = $TIMESTAMP
payload = collections.OrderedDict([
    ('id', 'a1b2c3d4e5f6a7b8'),
    ('isTV', False),
    ('loginStatus', collections.OrderedDict([
        ('pro', 0),
        ('status', 0),
        ('token', ''),
        ('unixtime', ts),
        ('user_id', 0)
    ])),
    ('packageName', 'com.streamdev.aiostreamer'),
    ('signatures', ['VQMyUhZdmnnwK5RVCbeGqu0HN020MEDUM44crQyL1zw=']),
    ('time', ts),
    ('version', 6643)
])
print(json.dumps(payload, separators=(',', ':')))
")
echo -n "$HASH_JSON" | openssl pkeyutl -encrypt -pubin -inkey /tmp/pubkey.pem -pkeyopt rsa_padding_mode:pkcs1 | openssl base64 -A | tr -d '\n\r'
