/**
 * Define repository that can be used to show list of entries.
 *
 * We can also add optimization of digest cycle (component site):
 * https://coderwall.com/p/d_aisq/speeding-up-angularjs-s-digest-loop
 */
((definition) => {
    if (typeof define === "function" && define.amd) {
        define([], definition);
    } else if (typeof module !== "undefined") {
        module.exports = definition();
    }
})(() => {
    "use strict";

    function createRepository(config) {
        let onNewItem = config.onNewItem;
        if (onNewItem === undefined) {
            onNewItem = () => {
            };
        }

        let itemDecorator = config.newItemDecorator;
        if (itemDecorator === undefined) {
            itemDecorator = (item) => {
            };
        }

        let filter = config.filter;
        if (filter === undefined) {
            filter = () => true;
        }

        let incrementalUpdate = config.itemSource.incrementalUpdateSupport;
        if (incrementalUpdate !== true) {
            incrementalUpdate = false;
        }

        return {
            // True once any data are loaded.
            "areDataReady": false,
            // True if there are no data in the data source.
            "isEmpty": true,
            // All the data retrieved from the source, contains also
            // filtered out items.
            "data": [],
            // Number of element that pass the filters.
            "filteredItemCount": 0,
            // Reference to item source.
            "_itemSource": config.itemSource,
            // Callback called for every new item.
            "_onNewItem": onNewItem,
            // Callback called before item is added to the repository.
            "_itemDecorator": itemDecorator,
            // True if repository minimal update ie. not by full data query.
            "_supportIncrementalUpdate": incrementalUpdate,
            // Used to add additional functionality to repository
            // called after data ara changed. Ie. after fetch, update,
            // filter change, ..
            "_onChange": () => {
            },
            // Function that returns ID for the given record.
            "_id": config.id,
            // Function used to filter items.
            "_filterFunction": filter,
            // Used to incremental update, key to used when fetching next data.
            "_lastUpdateStamp": undefined,
            "_orderFunction": config.order
        };
    }

    function initialFetch(repository) {
        if (repository.areDataReady) {
            return Promise.resolve();
        }
        onLoadingStarted(repository);
        return repository._itemSource.fetch().then((response) => {
            setRepositoryData(repository, response.data);
            if (repository._supportIncrementalUpdate) {
                repository._lastUpdateStamp = response.timeStamp;
            }
            onLoadingFinished(repository);
        }).catch((error) => {
            console.warn("Repository initial fetch failed.", error);
            onLoadingFailed(repository);
            throw error;
        });
    }

    function onLoadingStarted(repository) {
        repository.areDataReady = false;
    }

    function setRepositoryData(repository, data) {
        data.forEach(repository._onNewItem);
        data.forEach(repository._itemDecorator);
        filterItems(repository, data, undefined);
        repository.data = data;
        repository.isEmpty = data.length === 0;
        orderRepositoryItems(repository);
        callOnChange(repository);
    }

    function filterItems(repository, items, userData) {
        repository.filteredItemCount = 0;
        for (let index = 0; index < items.length; ++index) {
            const item = items[index];
            item["isVisible"] = repository._filterFunction(item, userData);
            repository.filteredItemCount += item["isVisible"];
        }
        callOnChange(repository);
    }

    function callOnChange(repository) {
        repository._onChange(repository);
    }

    function onLoadingFinished(repository) {
        repository.areDataReady = true;
    }

    function onLoadingFailed(repository) {
        repository.areDataReady = false;
    }

    /**
     * @param userData Is given to to filter function as additional argument.
     */
    function onFilterChange(repository, userData) {
        filterItems(repository, repository.data, userData);
        callOnChange(repository);
    }

    function updateItems(repository) {
        if (!repository.areDataReady) {
            console.warn("Calling update before finishing initial fetch, " +
                "calling initialFetch instead.");
            return initialFetch(repository);
        }
        if (repository._supportIncrementalUpdate &&
            repository._lastUpdateStamp !== undefined) {
            return incrementalUpdate(repository);
        } else {
            return fullUpdate(repository);
        }
    }

    function incrementalUpdate(repository) {
        const changedSince = repository._lastUpdateStamp;
        return repository._itemSource.fetch(changedSince).then((response) => {
            console.time("repository.incrementalUpdate");
            repository._lastUpdateStamp = response.timeStamp;
            if (updateRepositoryFromFetch(repository, response)) {
                // TODO Do not refresh all filters.
                filterItems(repository, repository.data);
                callOnChange(repository);
            }
            console.timeEnd("repository.incrementalUpdate");
        }).catch(onUpdateFailed);
    }

    function onUpdateFailed(error) {
        // TODO Propagate and show error message.
        console.error("Repository updating failed.", error);
    }

    function updateRepositoryFromFetch(repository, response) {
        if (response.data.length === 0 && response.tombstones.length === 0) {
            return false;
        }
        mergeItemsToRepository(repository, response.data);
        mergeTombstonesToRepository(repository, response.tombstones);
        orderRepositoryItems(repository);
        return true;
    }

    function mergeItemsToRepository(repository, newData) {
        newData.forEach((item) => {
            const index = getIndexOfItem(repository, repository._id(item));
            if (index === undefined) {
                repository._onNewItem(item);
                repository.data.push(item);
            } else {
                repository.data[index] = item;
            }
            repository._itemDecorator(item);
        });
    }

    function getIndexOfItem(repository, id) {
        // TODO We can optimize this with id-index map.
        for (let index = 0; index < repository.data.length; index++) {
            const indexId = repository._id(repository.data[index]);
            if (indexId === id) {
                return index;
            }
        }
        return undefined;
    }

    function mergeTombstonesToRepository(repository, tombstones) {
        if (tombstones === undefined) {
            return;
        }
        tombstones.forEach((id) => {
            const index = getIndexOfItem(repository, id);
            if (index === undefined) {
                return;
            }
            repository.data.splice(index, 1);
        });
    }

    function orderRepositoryItems(repository) {
        if (repository._orderFunction === undefined) {
            return;
        }
        repository.data.sort(repository._orderFunction);
    }

    function fullUpdate(repository) {
        return repository._itemSource.fetch().then((response) => {
            console.time("repository.fullUpdate");
            updateRepositoryFromFetch(repository, response);
            removeMissingItems(repository, response.data);
            // TODO OPTIMIZE Do not refresh all filters.
            filterItems(repository, repository.data);
            callOnChange(repository);
            console.timeEnd("repository.fullUpdate");
        }).catch(onUpdateFailed);
    }

    function removeMissingItems(repository, referenceData) {
        const data = repository.data;
        const referenceIds = new Set();
        referenceData.forEach(item => referenceIds.add(repository._id(item)));
        for (let index = data.length - 1; index >= 0; --index) {
            const id = repository._id(data[index]);
            if (referenceIds.has(id)) {
                continue;
            }
            data.splice(index, 1);
        }
    }

    function deleteItem(repository, item) {
        const id = repository._id(item);
        return repository._itemSource.deleteById(id).catch((error) => {
            console.warn("Repository delete request failed.", error);
            throw error;
        });
    }

    return {
        "create": createRepository,
        /**
         * Use to fetch data for the first time, once the data are loaded
         * it does not nothing.
         */
        "initialFetch": initialFetch,
        /**
         * Call when ever filters change to update the visibility of items in
         * repository.
         */
        "onFilterChange": onFilterChange,
        "update": updateItems,
        "deleteItem": deleteItem
    }

});