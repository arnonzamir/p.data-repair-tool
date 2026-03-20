import React, { useState, useEffect, useCallback } from 'react';
import { getNotes, addNote, getLists, addToList, removeFromList, createList, getListsForPurchase, getReviewStatus, setReviewStatus, PurchaseNote, PurchaseListData, ReviewStatus } from '../../api/client';

interface NotesAndListsProps {
  purchaseId: number;
  onListsChanged?: () => void;
}

function formatDateTime(v: string | null | undefined): string {
  if (!v) return '-';
  try {
    return new Date(v).toLocaleString();
  } catch { return v; }
}

const NotesAndLists: React.FC<NotesAndListsProps> = ({ purchaseId, onListsChanged }) => {
  const [notes, setNotes] = useState<PurchaseNote[]>([]);
  const [newNote, setNewNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [allLists, setAllLists] = useState<PurchaseListData[]>([]);
  const [memberLists, setMemberLists] = useState<string[]>([]);
  const [showAddList, setShowAddList] = useState(false);
  const [newListName, setNewListName] = useState('');
  const [confirmRemove, setConfirmRemove] = useState<string | null>(null);
  const [reviewStatus, setReviewStatusState] = useState<ReviewStatus | null>(null);

  const refreshData = useCallback(() => {
    getNotes(purchaseId).then(setNotes).catch(() => {});
    getLists().then(setAllLists).catch(() => {});
    getListsForPurchase(purchaseId).then(setMemberLists).catch(() => {});
    getReviewStatus(purchaseId).then(setReviewStatusState).catch(() => {});
  }, [purchaseId]);

  useEffect(() => { refreshData(); }, [refreshData]);

  const handleAddNote = async () => {
    if (!newNote.trim()) return;
    setSubmitting(true);
    try {
      await addNote(purchaseId, newNote.trim());
      setNewNote('');
      refreshData();
    } catch { /* ignore */ }
    setSubmitting(false);
  };

  const REVIEW_STATUSES = ['not-seen', 'at-work', 'done', 'need-fixing'] as const;

  const handleSetStatus = async (status: string) => {
    const result = await setReviewStatus(purchaseId, status);
    setReviewStatusState(result);
    if (onListsChanged) onListsChanged();
  };

  const handleRemoveFromList = async (listName: string) => {
    const list = allLists.find((l) => l.name === listName);
    if (list) {
      await removeFromList(list.id, purchaseId);
      setConfirmRemove(null);
      refreshData();
      if (onListsChanged) onListsChanged();
    }
  };

  const handleAddToList = async (listId: number) => {
    await addToList(listId, purchaseId);
    refreshData();
    setShowAddList(false);
    if (onListsChanged) onListsChanged();
  };

  const handleCreateAndAdd = async () => {
    if (!newListName.trim()) return;
    try {
      const list = await createList(newListName.trim());
      await addToList(list.id, purchaseId);
      setNewListName('');
      refreshData();
      setShowAddList(false);
      if (onListsChanged) onListsChanged();
    } catch { /* ignore */ }
  };

  // Lists this purchase is NOT already in
  const availableLists = allLists.filter((l) => !memberLists.includes(l.name));

  return (
    <div className="notes-and-lists">
      {/* Review status + lists */}
      <div className="review-status-row">
        <span className="lists-label">Status:</span>
        {REVIEW_STATUSES.map((s) => (
          <button
            key={s}
            className={`review-status-btn ${reviewStatus?.status === s ? `review-status-active review-status-${s}` : ''}`}
            onClick={() => handleSetStatus(s)}
          >
            {s}
          </button>
        ))}
        {reviewStatus?.updatedBy && (
          <span className="review-status-meta">by {reviewStatus.updatedBy}</span>
        )}
      </div>

      {/* Lists membership + add */}
      <div className="purchase-lists-row">
        <span className="lists-label">Lists:</span>
        {memberLists.length > 0 ? (
          memberLists.map((name) => (
            <span key={name} className="list-tag">
              {name}
              <span className="list-tag-remove-wrapper">
                <span className="list-tag-remove" onClick={() => setConfirmRemove(confirmRemove === name ? null : name)}>x</span>
                {confirmRemove === name && (
                  <span className="list-tag-confirm">
                    Remove?
                    <button className="btn btn-tiny btn-danger" onClick={() => handleRemoveFromList(name)}>Yes</button>
                    <button className="btn btn-tiny" onClick={() => setConfirmRemove(null)}>No</button>
                  </span>
                )}
              </span>
            </span>
          ))
        ) : (
          <span className="text-muted">none</span>
        )}
        <span className="add-to-list-wrapper">
          <button className="btn btn-tiny" onClick={() => setShowAddList(!showAddList)}>
            + Add to list
          </button>
          {showAddList && (
            <div className="add-to-list-dropdown">
              {availableLists.map((l) => (
                <div key={l.id} className="add-to-list-item" onClick={() => handleAddToList(l.id)}>
                  {l.name}
                </div>
              ))}
              <div className="add-to-list-create">
                <input
                  type="text"
                  value={newListName}
                  onChange={(e) => setNewListName(e.target.value)}
                  placeholder="New list name"
                  className="input-small"
                  onKeyDown={(e) => e.key === 'Enter' && handleCreateAndAdd()}
                />
                <button className="btn btn-tiny" onClick={handleCreateAndAdd} disabled={!newListName.trim()}>
                  Create
                </button>
              </div>
            </div>
          )}
        </span>
      </div>

      {/* Notes */}
      <div className="purchase-notes">
        <div className="notes-input-row">
          <textarea
            value={newNote}
            onChange={(e) => setNewNote(e.target.value)}
            placeholder="Add a note about this purchase..."
            className="notes-textarea"
            rows={2}
          />
          <button
            className="btn btn-small"
            onClick={handleAddNote}
            disabled={submitting || !newNote.trim()}
          >
            {submitting ? 'Saving...' : 'Add note'}
          </button>
        </div>
        {notes.length > 0 && (
          <div className="notes-list">
            {notes.map((note) => (
              <div key={note.id} className="note-item">
                <div className="note-meta">
                  <span className="note-author">{note.author}</span>
                  <span className="note-time">{formatDateTime(note.createdAt)}</span>
                </div>
                <div className="note-content">{note.content}</div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default NotesAndLists;
