#!/usr/bin/env python3
"""
지역 맛집 신뢰도 프로토타입 - 2단계.

구글 Places API(공식)의 rating, userRatingCount로 "평점은 높은데 리뷰가 거의
없는" 가게를 광고 의심으로 표시하고, 리뷰 텍스트는 ad_filter.py의 규칙으로
광고성 문구를 추가로 점검한다. 자세한 배경은 README.md와 data-source-design.md
참고.
"""

import json
import os
import sys
import urllib.request

from ad_filter import classify, score_review

API_KEY = os.environ.get("GOOGLE_PLACES_API_KEY")
SEARCH_URL = "https://places.googleapis.com/v1/places:searchText"

# 평점은 높은데 리뷰 수가 이 값보다 적으면 "광고 의심"으로 본다. 둘 다 추정치이며
# 실제 샘플로 돌려보고 조정이 필요하다.
HIGH_RATING_THRESHOLD = 4.7
MIN_TRUSTED_REVIEWS = 30


def search_place(query: str) -> dict | None:
    if not API_KEY:
        raise RuntimeError("GOOGLE_PLACES_API_KEY 환경 변수가 필요합니다.")

    body = json.dumps({"textQuery": query, "languageCode": "ko"}).encode("utf-8")
    req = urllib.request.Request(
        SEARCH_URL,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Goog-Api-Key": API_KEY,
            "X-Goog-FieldMask": (
                "places.displayName,places.formattedAddress,"
                "places.rating,places.userRatingCount,places.reviews"
            ),
        },
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.load(resp)

    places = data.get("places") or []
    return places[0] if places else None


def evaluate(place: dict) -> tuple[str, str]:
    rating = place.get("rating")
    review_count = place.get("userRatingCount")

    if rating is None or review_count is None:
        return "데이터 부족", "구글에 평점 또는 리뷰 수 정보가 없음"

    if rating >= HIGH_RATING_THRESHOLD and review_count < MIN_TRUSTED_REVIEWS:
        return "광고 의심", f"평점 {rating}인데 리뷰 {review_count}개뿐 (기준 {MIN_TRUSTED_REVIEWS}개 미달)"

    return "정상", f"평점 {rating}, 리뷰 {review_count}개"


def load_queries(path: str) -> list[str]:
    with open(path, encoding="utf-8") as f:
        return [line.strip() for line in f if line.strip() and not line.startswith("#")]


def check_reviews(place: dict) -> list[str]:
    """리뷰 텍스트별로 ad_filter 규칙을 적용해 사람이 읽을 요약 줄을 만든다."""
    lines = []
    for review in place.get("reviews") or []:
        text = (review.get("text") or {}).get("text", "")
        if not text:
            continue
        score, reasons = score_review(text)
        verdict = classify(score)
        if verdict != "일반 리뷰":
            snippet = text[:40] + ("…" if len(text) > 40 else "")
            lines.append(f"    - [{verdict}, {score}점] {snippet} ({'; '.join(reasons)})")
    return lines


def main() -> None:
    if len(sys.argv) != 2:
        print(f"사용법: {sys.argv[0]} stores.sample.txt")
        sys.exit(1)

    queries = load_queries(sys.argv[1])
    print(f"{'가게':<24}{'평점':<8}{'리뷰수':<10}{'판정':<10}이유")
    print("-" * 80)

    for query in queries:
        place = search_place(query)
        if place is None:
            print(f"{query:<24}{'':<8}{'':<10}{'검색 실패':<10}구글에서 찾을 수 없음")
            continue

        verdict, reason = evaluate(place)
        name = place.get("displayName", {}).get("text", query)
        rating = place.get("rating", "-")
        review_count = place.get("userRatingCount", "-")
        print(f"{name:<24}{rating!s:<8}{review_count!s:<10}{verdict:<10}{reason}")
        for line in check_reviews(place):
            print(line)


if __name__ == "__main__":
    main()
