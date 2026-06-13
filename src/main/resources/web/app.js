(() => {
    "use strict";

    const PAGE_SIZE = 10;
    const state = {
        query: "",
        ranking: true,
        offset: 0,
        total: 0,
        loading: false,
        results: []
    };

    const elements = {
        form: document.getElementById("searchForm"),
        input: document.getElementById("queryInput"),
        ranking: document.getElementById("rankingInput"),
        searchButton: document.getElementById("searchButton"),
        empty: document.getElementById("emptyState"),
        view: document.getElementById("resultsView"),
        list: document.getElementById("resultsList"),
        summary: document.getElementById("resultSummary"),
        timing: document.getElementById("timingSummary"),
        loadMore: document.getElementById("loadMoreButton"),
        error: document.getElementById("errorMessage"),
        template: document.getElementById("resultTemplate"),
        documentDialog: document.getElementById("documentDialog"),
        documentTitle: document.getElementById("documentTitle"),
        documentMeta: document.getElementById("documentMeta"),
        documentText: document.getElementById("documentText"),
        documentLoading: document.getElementById("documentLoading"),
        helpDialog: document.getElementById("helpDialog")
    };

    elements.form.addEventListener("submit", event => {
        event.preventDefault();
        search(true);
    });
    elements.loadMore.addEventListener("click", () => search(false));
    document.getElementById("closeDialogButton").addEventListener("click", () => elements.documentDialog.close());
    document.getElementById("helpButton").addEventListener("click", () => elements.helpDialog.showModal());
    document.getElementById("closeHelpButton").addEventListener("click", () => elements.helpDialog.close());
    document.querySelector(".example-query").addEventListener("click", event => {
        elements.input.value = event.currentTarget.textContent;
        search(true);
    });
    document.querySelectorAll("[data-token]").forEach(button => {
        button.addEventListener("click", () => insertToken(button.dataset.token));
    });

    async function search(reset) {
        if (state.loading) return;
        const query = elements.input.value.trim();
        if (!query) {
            elements.input.focus();
            return;
        }
        if (reset) {
            state.query = query;
            state.ranking = elements.ranking.checked;
            state.offset = 0;
            state.results = [];
            elements.list.replaceChildren();
            const url = new URL(window.location.href);
            url.searchParams.set("q", state.query);
            url.searchParams.set("ranking", String(state.ranking));
            window.history.replaceState(null, "", url);
        }

        setLoading(true);
        showError("");
        const browserStarted = performance.now();
        const parameters = new URLSearchParams({
            q: state.query,
            offset: String(state.offset),
            limit: String(PAGE_SIZE),
            ranking: String(state.ranking)
        });

        try {
            const response = await fetch(`/api/search?${parameters}`);
            const payload = await response.json();
            if (!response.ok) throw new Error(payload.error || "Ошибка поиска");

            payload.results.forEach((result, index) => {
                state.results.push(result);
                elements.list.append(renderResult(result, payload.offset + index + 1));
            });
            state.offset = state.results.length;
            state.total = payload.total;
            elements.empty.hidden = true;
            elements.view.hidden = false;
            elements.summary.textContent = pluralizePages(payload.total);
            elements.loadMore.hidden = !payload.hasMore;

            requestAnimationFrame(() => {
                const browserMs = performance.now() - browserStarted;
                elements.timing.textContent =
                    `Поиск: ${formatMs(payload.elapsedMs)} · на экране за ${formatMs(browserMs)} · показано ${state.results.length}`;
            });
        } catch (error) {
            elements.empty.hidden = true;
            elements.view.hidden = false;
            showError(error.message);
            elements.loadMore.hidden = true;
        } finally {
            setLoading(false);
        }
    }

    function renderResult(result, rank) {
        const fragment = elements.template.content.cloneNode(true);
        const card = fragment.querySelector(".result-card");
        card.querySelector(".result-rank").textContent = rank;
        card.querySelector(".page-id").textContent = `Wikipedia page ${result.pageId}`;
        card.querySelector(".result-title").textContent = `Страница ${result.pageId}`;
        card.querySelector(".score-badge").textContent =
            state.ranking ? `BM25 ${result.score.toFixed(4)}` : `docID ${result.docId}`;

        const terms = Object.keys(result.terms);
        const snippet = card.querySelector(".snippet");
        if (result.snippet) {
            renderHighlighted(snippet, result.snippet, terms);
        } else {
            snippet.textContent = "Snippet отсутствует.";
        }

        const details = card.querySelector(".term-details");
        Object.entries(result.terms).forEach(([term, info]) => {
            const chip = document.createElement("span");
            chip.className = "term-chip";
            const shown = info.positions.join(", ");
            chip.textContent = `${term}: [${shown}${info.total > info.positions.length ? ", …" : ""}]`;
            chip.title = `Всего позиций: ${info.total}`;
            details.append(chip);
        });

        card.querySelector(".open-document-button").addEventListener("click", () => openDocument(result, terms));
        return fragment;
    }

    async function openDocument(result, terms) {
        elements.documentTitle.textContent = "Загрузка...";
        elements.documentMeta.textContent = `Wikipedia page ${result.pageId}`;
        elements.documentText.hidden = true;
        elements.documentText.replaceChildren();
        elements.documentLoading.hidden = false;
        elements.documentLoading.textContent = "Читаем и форматируем страницу из XML dump...";
        elements.documentDialog.showModal();

        try {
            const response = await fetch(`/api/document?pageId=${encodeURIComponent(result.pageId)}`);
            const payload = await response.json();
            if (!response.ok) throw new Error(payload.error || "Не удалось открыть документ");
            elements.documentTitle.textContent = payload.title || `Страница ${payload.pageId}`;
            const occurrences = countOccurrences(payload.text, terms);
            const occurrenceSummary = Object.entries(occurrences)
                .map(([term, count]) => `${term}: ${count}`)
                .join(" · ");
            elements.documentMeta.textContent =
                `Wikipedia page ${payload.pageId} · docID ${result.docId}`
                + (occurrenceSummary ? ` · вхождения: ${occurrenceSummary}` : "");
            renderHighlighted(elements.documentText, payload.text, terms);
            elements.documentLoading.hidden = true;
            elements.documentText.hidden = false;
        } catch (error) {
            elements.documentLoading.textContent = error.message;
        }
    }

    function renderHighlighted(container, text, terms) {
        container.replaceChildren();
        const unique = [...new Set(terms.filter(Boolean))].sort((a, b) => b.length - a.length);
        if (!unique.length) {
            container.textContent = text;
            return;
        }
        const expression = new RegExp(unique.map(escapeRegex).join("|"), "giu");
        let last = 0;
        for (const match of text.matchAll(expression)) {
            container.append(document.createTextNode(text.slice(last, match.index)));
            const mark = document.createElement("mark");
            mark.textContent = match[0];
            container.append(mark);
            last = match.index + match[0].length;
        }
        container.append(document.createTextNode(text.slice(last)));
    }

    function countOccurrences(text, terms) {
        const occurrences = {};
        for (const term of new Set(terms.filter(Boolean))) {
            const expression = new RegExp(escapeRegex(term), "giu");
            occurrences[term] = [...text.matchAll(expression)].length;
        }
        return occurrences;
    }

    function insertToken(token) {
        const input = elements.input;
        const start = input.selectionStart ?? input.value.length;
        const end = input.selectionEnd ?? start;
        input.value = input.value.slice(0, start) + token + input.value.slice(end);
        const cursor = start + token.length;
        input.focus();
        input.setSelectionRange(cursor, cursor);
    }

    function setLoading(loading) {
        state.loading = loading;
        elements.searchButton.disabled = loading;
        elements.loadMore.disabled = loading;
        elements.searchButton.textContent = loading ? "Ищем..." : "Найти";
    }

    function showError(message) {
        elements.error.hidden = !message;
        elements.error.textContent = message;
    }

    function pluralizePages(total) {
        const mod10 = total % 10;
        const mod100 = total % 100;
        const noun = mod10 === 1 && mod100 !== 11
            ? "страница"
            : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)
                ? "страницы"
                : "страниц";
        return `Найдено ${total.toLocaleString("ru-RU")} ${noun}`;
    }

    function formatMs(value) {
        return `${Number(value).toLocaleString("ru-RU", { maximumFractionDigits: 1 })} мс`;
    }

    function escapeRegex(value) {
        return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    }

    const initialParameters = new URLSearchParams(window.location.search);
    const initialQuery = initialParameters.get("q");
    if (initialQuery) {
        elements.input.value = initialQuery;
        if (initialParameters.has("ranking")) {
            elements.ranking.checked = initialParameters.get("ranking") !== "false";
        }
        search(true);
    }
})();
