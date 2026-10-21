#!/usr/bin/env python3
"""
카카오 로컬 API(키워드 장소검색)로 기본정보만 조회.

평점·리뷰는 카카오 API에 없다 — 이 스크립트는 이름/주소/카테고리/전화번호 같은
기본정보 전용이고, 신뢰도 신호(평점/리뷰)는 trust_score.py(구글 Places)가
맡는다. 역할 분리 배경은 data-source-design.md 참고.
"""

import json
import os
import sys
import urllib.parse
import urllib.request

API_KEY = os.environ.get("KAKAO_REST_API_KEY")
SEARCH_URL = "https://dapi.kakao.com/v2/local/search/keyword.json"


def search_place(query: str) -> dict | None:
    if not API_KEY:
        raise RuntimeError("KAKAO_REST_API_KEY 환경 변수가 필요합니다.")

    url = f"{SEARCH_URL}?{urllib.parse.urlencode({'query': query})}"
    req = urllib.request.Request(url, headers={"Authorization": f"KakaoAK {API_KEY}"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.load(resp)

    documents = data.get("documents") or []
    return documents[0] if documents else None


def load_queries(path: str) -> list[str]:
    with open(path, encoding="utf-8") as f:
        return [line.strip() for line in f if line.strip() and not line.startswith("#")]


def main() -> None:
    if len(sys.argv) != 2:
        print(f"사용법: {sys.argv[0]} stores.sample.txt")
        sys.exit(1)

    queries = load_queries(sys.argv[1])
    print(f"{'가게':<24}{'카테고리':<20}{'전화번호':<16}주소")
    print("-" * 90)

    for query in queries:
        place = search_place(query)
        if place is None:
            print(f"{query:<24}{'':<20}{'':<16}카카오에서 찾을 수 없음")
            continue

        name = place.get("place_name", query)
        category = place.get("category_group_name") or place.get("category_name", "-")
        phone = place.get("phone") or "-"
        address = place.get("road_address_name") or place.get("address_name", "-")
        print(f"{name:<24}{category:<20}{phone:<16}{address}")


if __name__ == "__main__":
    main()
